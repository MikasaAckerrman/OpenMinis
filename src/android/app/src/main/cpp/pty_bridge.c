// pty_bridge.c — JNI wrapper around bionic forkpty()/tcsetattr()/ioctl()
//
// Enables a real TTY for a spawned proot/sh process so the shell emits its PS1
// prompt, echoes characters back as typed, and responds to arrow keys,
// Ctrl+C as SIGINT, etc. — mirroring iOS ISHKernel behaviour.
//
// Bionic libc exposes forkpty() since API 21 (Android 5.0). This file wraps
// that plus read/write/ioctl on the master fd and process waitpid.

#include <jni.h>
#include <pty.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>
#include <errno.h>
#include <string.h>
#include <stdlib.h>
#include <fcntl.h>
#include <signal.h>
#include <stdint.h>

// Convert a jstring to a freshly malloc'd standard-UTF-8 string.
//
// [T-pty-utf8] GetStringUTFChars would hand back Modified UTF-8, in which a
// character outside the BMP is a surrogate pair written as two 3-byte
// sequences. execve wants standard UTF-8, where the same character is one
// 4-byte sequence, so an emoji in a command, an argument, an environment
// variable or a working directory reached the shell as three invalid bytes.
// Walking the UTF-16 units ourselves produces the encoding execve actually
// expects. Returns NULL only on allocation failure; an empty string is a
// legitimate result and is returned as "".
//
// Caller frees with free(), not ReleaseStringUTFChars.
static char *jstring_to_utf8(JNIEnv *env, jstring s) {
    if (s == NULL) return strdup("");
    const jsize len = (*env)->GetStringLength(env, s);
    if (len <= 0) return strdup("");
    const jchar *units = (*env)->GetStringChars(env, s, NULL);
    if (units == NULL) return NULL;
    // At most 3 bytes per UTF-16 unit: BMP characters encode to three, and a
    // surrogate pair consumes two units for four bytes.
    char *out = (char *) malloc((size_t) len * 3 + 4);
    if (out == NULL) {
        (*env)->ReleaseStringChars(env, s, units);
        return NULL;
    }
    size_t o = 0;
    for (jsize i = 0; i < len; i++) {
        uint32_t cp = units[i];
        if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < len) {
            const uint32_t low = units[i + 1];
            if (low >= 0xDC00 && low <= 0xDFFF) {
                cp = 0x10000 + ((cp - 0xD800) << 10) + (low - 0xDC00);
                i++;
            } else {
                cp = 0xFFFD;  // high surrogate with no low surrogate after it
            }
        } else if (cp >= 0xD800 && cp <= 0xDFFF) {
            cp = 0xFFFD;      // stray low surrogate
        }
        if (cp <= 0x7F) {
            out[o++] = (char) cp;
        } else if (cp <= 0x7FF) {
            out[o++] = (char) (0xC0 | (cp >> 6));
            out[o++] = (char) (0x80 | (cp & 0x3F));
        } else if (cp <= 0xFFFF) {
            out[o++] = (char) (0xE0 | (cp >> 12));
            out[o++] = (char) (0x80 | ((cp >> 6) & 0x3F));
            out[o++] = (char) (0x80 | (cp & 0x3F));
        } else {
            out[o++] = (char) (0xF0 | (cp >> 18));
            out[o++] = (char) (0x80 | ((cp >> 12) & 0x3F));
            out[o++] = (char) (0x80 | ((cp >> 6) & 0x3F));
            out[o++] = (char) (0x80 | (cp & 0x3F));
        }
    }
    out[o] = '\0';
    (*env)->ReleaseStringChars(env, s, units);
    return out;
}
#include <android/log.h>

#define LOG_TAG "PtyBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Fork the process into a new PTY and exec the child command.
// Returns the PTY master fd on success. Child pid stored into outPid[0].
// On failure returns -errno.
JNIEXPORT jint JNICALL
Java_com_openminis_app_sandbox_PtyBridge_forkExec(
    JNIEnv *env, jclass clazz,
    jstring jCmd, jobjectArray jArgv, jobjectArray jEnvp,
    jstring jCwd, jint cols, jint rows, jintArray outPid)
{
    char *cmd = jstring_to_utf8(env, jCmd);
    if (!cmd) return -ENOMEM;

    // Build argv[] — owned strings copied from Java.
    jsize argc = (*env)->GetArrayLength(env, jArgv);
    char **argv = (char **) calloc(argc + 1, sizeof(char *));
    if (!argv) {
        free(cmd);
        return -ENOMEM;
    }
    for (jsize i = 0; i < argc; i++) {
        jstring s = (jstring) (*env)->GetObjectArrayElement(env, jArgv, i);
        char *cs = jstring_to_utf8(env, s);
        argv[i] = cs ? cs : strdup("");
        (*env)->DeleteLocalRef(env, s);
    }
    argv[argc] = NULL;

    // Build envp[] — same pattern.
    jsize envc = jEnvp ? (*env)->GetArrayLength(env, jEnvp) : 0;
    char **envp = (char **) calloc(envc + 1, sizeof(char *));
    if (!envp) {
        for (jsize i = 0; i < argc; i++) free(argv[i]);
        free(argv);
        free(cmd);
        return -ENOMEM;
    }
    for (jsize i = 0; i < envc; i++) {
        jstring s = (jstring) (*env)->GetObjectArrayElement(env, jEnvp, i);
        char *cs = jstring_to_utf8(env, s);
        envp[i] = cs ? cs : strdup("");
        (*env)->DeleteLocalRef(env, s);
    }
    envp[envc] = NULL;

    char *cwd = jCwd ? jstring_to_utf8(env, jCwd) : NULL;

    // Configure initial window size.
    struct winsize ws;
    ws.ws_col = (cols > 0) ? (unsigned short) cols : 80;
    ws.ws_row = (rows > 0) ? (unsigned short) rows : 24;
    ws.ws_xpixel = 0;
    ws.ws_ypixel = 0;

    // Default termios: echo ON, ICANON OFF (raw-ish so shell line editor works).
    // We actually want canonical-ish mode — the shell's readline/line editor
    // manages its own char-at-a-time state, so leave the classic defaults and
    // let the shell reconfigure termios via tcsetattr() itself.
    struct termios tio;
    memset(&tio, 0, sizeof(tio));
    tio.c_iflag = ICRNL | IXON | IUTF8;
    tio.c_oflag = OPOST | ONLCR;
    tio.c_cflag = CREAD | CS8 | HUPCL;
    tio.c_lflag = ISIG | ICANON | ECHO | ECHOE | ECHOK | IEXTEN;
    cfsetispeed(&tio, B38400);
    cfsetospeed(&tio, B38400);
    tio.c_cc[VINTR]    = 0x03; // Ctrl-C
    tio.c_cc[VQUIT]    = 0x1c; // Ctrl-\.
    tio.c_cc[VERASE]   = 0x7f; // Backspace (DEL)
    tio.c_cc[VKILL]    = 0x15; // Ctrl-U
    tio.c_cc[VEOF]     = 0x04; // Ctrl-D
    tio.c_cc[VSTART]   = 0x11;
    tio.c_cc[VSTOP]    = 0x13;
    tio.c_cc[VSUSP]    = 0x1a; // Ctrl-Z
    tio.c_cc[VMIN]     = 1;
    tio.c_cc[VTIME]    = 0;

    int masterFd = -1;
    pid_t pid = forkpty(&masterFd, NULL, &tio, &ws);
    if (pid < 0) {
        int err = errno;
        LOGE("forkpty failed: %s", strerror(err));
        for (jsize i = 0; i < argc; i++) free(argv[i]);
        free(argv);
        for (jsize i = 0; i < envc; i++) free(envp[i]);
        free(envp);
        free(cmd);
        free(cwd);
        return -err;
    }

    if (pid == 0) {
        // Child.
        if (cwd && *cwd) chdir(cwd);
        // Clear all signal handlers and unblock the usual set so the shell's
        // own signal handling works as users expect.
        sigset_t empty;
        sigemptyset(&empty);
        sigprocmask(SIG_SETMASK, &empty, NULL);
        for (int s = 1; s < NSIG; s++) signal(s, SIG_DFL);

        execve(cmd, argv, envp);
        // execve() only returns on failure.
        _exit(127);
    }

    // Parent.
    for (jsize i = 0; i < argc; i++) free(argv[i]);
    free(argv);
    for (jsize i = 0; i < envc; i++) free(envp[i]);
    free(envp);
    free(cmd);
    free(cwd);

    jint pidOut = (jint) pid;
    (*env)->SetIntArrayRegion(env, outPid, 0, 1, &pidOut);
    LOGI("forkpty ok: pid=%d masterFd=%d cols=%d rows=%d", pid, masterFd, cols, rows);
    return masterFd;
}

JNIEXPORT jint JNICALL
Java_com_openminis_app_sandbox_PtyBridge_readBytes(
    JNIEnv *env, jclass clazz, jint fd, jbyteArray buf, jint off, jint len)
{
    if (fd < 0 || len <= 0) return -EINVAL;
    jbyte *data = (*env)->GetByteArrayElements(env, buf, NULL);
    if (!data) return -ENOMEM;
    ssize_t n = read(fd, data + off, (size_t) len);
    int err = (n < 0) ? errno : 0;
    (*env)->ReleaseByteArrayElements(env, buf, data, 0);
    if (n < 0) return -err;
    return (jint) n;
}

JNIEXPORT jint JNICALL
Java_com_openminis_app_sandbox_PtyBridge_writeBytes(
    JNIEnv *env, jclass clazz, jint fd, jbyteArray buf, jint off, jint len)
{
    if (fd < 0 || len <= 0) return -EINVAL;
    jbyte *data = (*env)->GetByteArrayElements(env, buf, NULL);
    if (!data) return -ENOMEM;
    ssize_t written = 0;
    while (written < len) {
        ssize_t n = write(fd, data + off + written, (size_t) (len - written));
        if (n < 0) {
            if (errno == EINTR) continue;
            int err = errno;
            (*env)->ReleaseByteArrayElements(env, buf, data, JNI_ABORT);
            return -err;
        }
        written += n;
    }
    (*env)->ReleaseByteArrayElements(env, buf, data, JNI_ABORT);
    return (jint) written;
}

JNIEXPORT jint JNICALL
Java_com_openminis_app_sandbox_PtyBridge_setWindowSize(
    JNIEnv *env, jclass clazz, jint fd, jint cols, jint rows)
{
    if (fd < 0) return -EINVAL;
    struct winsize ws;
    ws.ws_col = (unsigned short) cols;
    ws.ws_row = (unsigned short) rows;
    ws.ws_xpixel = 0;
    ws.ws_ypixel = 0;
    if (ioctl(fd, TIOCSWINSZ, &ws) < 0) return -errno;
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_openminis_app_sandbox_PtyBridge_closeFd(
    JNIEnv *env, jclass clazz, jint fd)
{
    if (fd < 0) return 0;
    if (close(fd) < 0) return -errno;
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_openminis_app_sandbox_PtyBridge_sendSignal(
    JNIEnv *env, jclass clazz, jint pid, jint sig)
{
    if (pid <= 0) return -EINVAL;
    if (kill((pid_t) pid, sig) < 0) return -errno;
    return 0;
}

// Blocking wait for the given child pid. Returns the exit status
// (WEXITSTATUS) on normal exit, or -(128+sig) on termination by signal.
// Returns -errno on waitpid() failure.
JNIEXPORT jint JNICALL
Java_com_openminis_app_sandbox_PtyBridge_waitFor(
    JNIEnv *env, jclass clazz, jint pid)
{
    if (pid <= 0) return -EINVAL;
    int status = 0;
    while (1) {
        pid_t r = waitpid((pid_t) pid, &status, 0);
        if (r < 0) {
            if (errno == EINTR) continue;
            return -errno;
        }
        break;
    }
    if (WIFEXITED(status)) return (jint) WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return (jint) -(128 + WTERMSIG(status));
    return -1;
}
