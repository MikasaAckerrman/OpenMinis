// T283 — native crash → file (NDK signal handler).
//
// Registered at app startup from MinisApp.onCreate via JNI. Catches
// fatal signals raised inside JNI / proot / pty_bridge / any other
// native code, writes a one-shot text report to the configured logs
// dir, then restores the default handler and re-raises so the system
// tombstone is also generated and the app exits like normal.
//
// Strict async-signal-safety: only signal-safe libc calls inside the
// handler (open/write/close/snprintf are safe; printf/malloc are not).

#include <jni.h>
#include <signal.h>
#include <unistd.h>
#include <fcntl.h>
#include <cstring>
#include <cstdio>
#include <ctime>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <android/log.h>
// [T-native-crash-backtrace] LLVM's unwinder — available at every API
// level (unlike bionic execinfo backtrace(), which needs API 33 and we
// target minSdk 26). Does not malloc; walks CFI/frame-pointer tables.
#include <unwind.h>
// [T-native-crash-backtrace] dladdr resolves each frame's module so the
// report says "libproot/libc/libapp + offset" — module attribution alone
// converts an anonymous SIGABRT into a named suspect. dladdr does no
// malloc on bionic; if it faults on a corrupted heap, the reentrancy
// guard above routes the second signal straight to SIG_DFL.
#include <dlfcn.h>

#define LOG_TAG "MinisCrashHandler"

// Plenty of headroom for "<logs_dir>/native-crash-YYYY-MM-DD_HH-MM-SS.log".
static char g_log_dir[512] = {0};

// Reentrancy guard. If the handler crashes itself, we want the second
// signal to skip straight to SIG_DFL rather than recursing.
static volatile sig_atomic_t g_in_handler = 0;

// Signal name lookup — strsignal() is NOT async-signal-safe on all
// libc implementations, so use a hardcoded table.
static const char* signal_name(int sig) {
    switch (sig) {
        case SIGSEGV: return "SIGSEGV";
        case SIGABRT: return "SIGABRT";
        case SIGBUS:  return "SIGBUS";
        case SIGFPE:  return "SIGFPE";
        case SIGILL:  return "SIGILL";
        case SIGSYS:  return "SIGSYS";
        case SIGTRAP: return "SIGTRAP";
        default:      return "UNKNOWN";
    }
}

// [T-native-crash-backtrace] ---- stack capture ----
// Context shared between the unwinder callback and the handler. The fd is
// kept open across frames; each frame is formatted into a stack buffer
// (no malloc) and written with a partial-write loop.
#define MAX_UNWIND_FRAMES 32

struct UnwindCtx {
    int fd;
    int count;
    char buf[256];
};

static void write_all(int fd, const char* buf, int len) {
    ssize_t written = 0;
    while (written < len) {
        ssize_t w = write(fd, buf + written, len - written);
        if (w <= 0) break;
        written += w;
    }
}

static _Unwind_Reason_Code unwind_frame_cb(struct _Unwind_Context* uctx, void* data) {
    UnwindCtx* out = static_cast<UnwindCtx*>(data);
    if (out->count >= MAX_UNWIND_FRAMES) return _URC_END_OF_STACK;

    uintptr_t pc = (uintptr_t)_Unwind_GetIP(uctx);
    if (pc == 0) return _URC_CONTINUE_UNWIND;

    // Module attribution: absolute pc → "<soname>+<offset from base>".
    // Skips the anonymous-signal-crash problem: even without symbol
    // names, the module says who aborted.
    const char* module = "?";
    uintptr_t base = 0;
    Dl_info dli;
    if (dladdr(reinterpret_cast<void*>(pc), &dli) != 0 && dli.dli_fname != nullptr) {
        module = dli.dli_fname;
        base = reinterpret_cast<uintptr_t>(dli.dli_fbase);
    }

    int n = snprintf(out->buf, sizeof(out->buf),
        "  #%02d pc 0x%016lx  %s+0x%lx\n",
        out->count, (unsigned long)pc, module,
        (unsigned long)(pc >= base ? pc - base : 0));
    if (n > 0) write_all(out->fd, out->buf, n);
    out->count++;
    return _URC_CONTINUE_UNWIND;
}

// Captures the current (faulting-thread) stack into fd. Runs inside the
// signal handler: frame #0 is the handler itself, #1 the signal
// trampoline, deeper frames are the interrupted aborter — on arm64
// frame-pointer chains cross the signal frame in libc/NDK code.
static void write_backtrace(int fd) {
    static const char hdr[] = "\nBacktrace (arm64 unwind, module+off):\n";
    write_all(fd, hdr, (int)sizeof(hdr) - 1);
    UnwindCtx ctx{fd, 0, {0}};
    _Unwind_Backtrace(unwind_frame_cb, &ctx);
    if (ctx.count == 0) {
        static const char none[] = "  <unavailable: unwinder returned 0 frames>\n";
        write_all(fd, none, (int)sizeof(none) - 1);
    }
}

static void crash_signal_handler(int sig, siginfo_t* info, void* ctx) {
    // Reentrancy: if we're already in the handler, just restore default
    // and re-raise. Avoids infinite loop when the handler itself faults.
    if (g_in_handler) {
        signal(sig, SIG_DFL);
        raise(sig);
        return;
    }
    g_in_handler = 1;

    if (g_log_dir[0] == 0) {
        signal(sig, SIG_DFL);
        raise(sig);
        return;
    }

    // Build the per-crash filename. time(NULL) is async-signal-safe;
    // localtime_r is too on bionic. snprintf is documented async-safe
    // by POSIX.
    time_t now = time(nullptr);
    struct tm tm_buf;
    localtime_r(&now, &tm_buf);

    char path[640];
    snprintf(path, sizeof(path),
        "%s/native-crash-%04d-%02d-%02d_%02d-%02d-%02d.log",
        g_log_dir,
        tm_buf.tm_year + 1900, tm_buf.tm_mon + 1, tm_buf.tm_mday,
        tm_buf.tm_hour, tm_buf.tm_min, tm_buf.tm_sec);

    int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (fd < 0) {
        signal(sig, SIG_DFL);
        raise(sig);
        return;
    }

    char buf[1024];
    int n = snprintf(buf, sizeof(buf),
        "=== Minis Native Crash ===\n"
        "Time: %04d-%02d-%02d %02d:%02d:%02d\n"
        "Signal: %d (%s)\n"
        "si_code: %d\n"
        "Fault addr: %p\n"
        "PID: %d  TID: %d\n"
        "\n"
        "(Tombstone with full backtrace written by Android system to "
        "/data/tombstones/ — adb pull or `adb bugreport`.)\n",
        tm_buf.tm_year + 1900, tm_buf.tm_mon + 1, tm_buf.tm_mday,
        tm_buf.tm_hour, tm_buf.tm_min, tm_buf.tm_sec,
        sig, signal_name(sig),
        info ? info->si_code : -1,
        info ? info->si_addr : nullptr,
        getpid(), (int)syscall(SYS_gettid));
    if (n > 0) {
        ssize_t written = 0;
        while (written < n) {
            ssize_t w = write(fd, buf + written, n - written);
            if (w <= 0) break;
            written += w;
        }
    }
    // [T-native-crash-thread] The crashing THREAD's name — the single
    // best discriminator of the subsystem (OkHttp worker / pty bridge /
    // proot / coroutine dispatcher / unnamed pthread). All three SIGABRTs
    // of 01.10 died in worker threads we could not name.
    {
        char comm_path[80];
        snprintf(comm_path, sizeof(comm_path), "/proc/self/task/%d/comm",
                 (int)syscall(SYS_gettid));
        int cfd = open(comm_path, O_RDONLY);
        if (cfd >= 0) {
            char comm[40] = {0};
            ssize_t r = read(cfd, comm, sizeof(comm) - 1);
            close(cfd);
            if (r > 0) {
                if (comm[r - 1] == '\n') comm[r - 1] = 0;
                char tb[80];
                int tn = snprintf(tb, sizeof(tb), "Thread: %s\n", comm);
                if (tn > 0) {
                    ssize_t w2 = 0;
                    while (w2 < tn) {
                        ssize_t w = write(fd, tb + w2, tn - w2);
                        if (w <= 0) break;
                        w2 += w;
                    }
                }
            }
        }
    }
    // [T-native-crash-backtrace] In-process frames of the aborting thread
    // — for OUR handler-caught signals the system tombstone often never
    // exists (the re-raise races debuggerd), so this is the only frame
    // attribution we get. Module+offset names the suspect even without
    // symbol tables.
    write_backtrace(fd);
    close(fd);

    // Re-raise with default handler so Android still produces a tombstone
    // and ActivityManager handles process-death the normal way.
    struct sigaction sa{};
    sa.sa_handler = SIG_DFL;
    sigemptyset(&sa.sa_mask);
    sigaction(sig, &sa, nullptr);
    raise(sig);
}

extern "C" JNIEXPORT void JNICALL
Java_com_openminis_app_crash_NativeCrashHandler_nativeInstall(
        JNIEnv* env, jobject /*thiz*/, jstring jLogDir) {
    if (jLogDir == nullptr) return;
    const char* dir = env->GetStringUTFChars(jLogDir, nullptr);
    if (dir == nullptr) return;
    strncpy(g_log_dir, dir, sizeof(g_log_dir) - 1);
    g_log_dir[sizeof(g_log_dir) - 1] = 0;
    env->ReleaseStringUTFChars(jLogDir, dir);

    // mkdir is fine here — we're on the JVM thread, not in a signal.
    mkdir(g_log_dir, 0755);

    struct sigaction sa{};
    sa.sa_sigaction = crash_signal_handler;
    sa.sa_flags = SA_SIGINFO;
    sigemptyset(&sa.sa_mask);

    // Register for the signals that map to JNI/native bugs we actually
    // want to capture. SIGABRT covers __android_log_assert / abort()
    // from libc; SIGSEGV/BUS/ILL cover most JNI memory bugs; SIGFPE
    // covers integer div-by-zero. SIGSYS catches seccomp violations
    // (proot occasionally trips these on new kernels).
    sigaction(SIGSEGV, &sa, nullptr);
    sigaction(SIGABRT, &sa, nullptr);
    sigaction(SIGBUS,  &sa, nullptr);
    sigaction(SIGFPE,  &sa, nullptr);
    sigaction(SIGILL,  &sa, nullptr);
    sigaction(SIGSYS,  &sa, nullptr);

    __android_log_print(ANDROID_LOG_INFO, LOG_TAG,
        "installed: dir=%s", g_log_dir);
}
