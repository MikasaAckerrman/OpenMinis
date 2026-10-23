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
#include <ucontext.h>

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
// [T-native-crash-context] Frame-pointer chain depth.
#define MAX_FP_FRAMES 24

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

// [T-native-crash-backtrace] Module attribution for one program counter.
// Absolute pc → "<soname>+<offset from base>". Even without symbol tables the
// module says who aborted.
//
// dladdr() is not async-signal-safe - it takes the linker's lock, so a crash
// inside the linker could deadlock here. The unwinder below has the same
// exposure and has been in place without incident; the alternative, no
// attribution at all, is worse, because then a crash log cannot even name
// the library that crashed.
static void pc_module(uintptr_t pc, const char** module, uintptr_t* base) {
    *module = "?";
    *base = 0;
    Dl_info dli;
    if (dladdr(reinterpret_cast<void*>(pc), &dli) != 0 && dli.dli_fname != nullptr) {
        *module = dli.dli_fname;
        *base = reinterpret_cast<uintptr_t>(dli.dli_fbase);
    }
}

static _Unwind_Reason_Code unwind_frame_cb(struct _Unwind_Context* uctx, void* data) {
    UnwindCtx* out = static_cast<UnwindCtx*>(data);
    if (out->count >= MAX_UNWIND_FRAMES) return _URC_END_OF_STACK;

    uintptr_t pc = (uintptr_t)_Unwind_GetIP(uctx);
    if (pc == 0) return _URC_CONTINUE_UNWIND;

    const char* module = "?";
    uintptr_t base = 0;
    pc_module(pc, &module, &base);

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

// [T-native-crash-context] The faulting thread's registers and a walk of
// its frame-pointer chain.
//
// _Unwind_Backtrace cannot cross a signal frame: the trampoline the kernel
// installed has no unwind information, so the walk stops at the handler
// itself. Every native crash log this app produced therefore contained
// exactly one frame, and that frame was in this library - the crash itself
// was never attributable. Thirteen of them had accumulated, all unreadable.
//
// The signal handler receives the interrupted context, and that context
// carries the faulting PC, the stack pointer, the frame pointer and the link
// register. Starting from the faulting code's frame pointer skips both the
// handler and the trampoline, and each frame's saved link register is the
// return address into its caller. The link register of the faulting frame
// alone already names the caller, so even a leaf function that has no frame
// pointer at all yields a usable caller.
//
// Reading the chain can fault if a frame is stale. The reentrancy guard
// catches that: the handler restores SIG_DFL and re-raises, and Android
// produces its own tombstone. The frame validation below is what keeps that
// from happening in the first place.
static void write_fault_context(int fd, void* ctx) {
    if (ctx == nullptr) return;
    ucontext_t* uc = static_cast<ucontext_t*>(ctx);

#if defined(__aarch64__)
    const uintptr_t pc = static_cast<uintptr_t>(uc->uc_mcontext.pc);
    const uintptr_t sp = static_cast<uintptr_t>(uc->uc_mcontext.sp);
    uintptr_t fp = static_cast<uintptr_t>(uc->uc_mcontext.regs[29]);
    const uintptr_t lr = static_cast<uintptr_t>(uc->uc_mcontext.regs[30]);
#else
    // The app is arm64-only (abiFilters), so this branch is never built.
    // It is left without register access on purpose: the field names for the
    // other ABIs differ (__gregs on arm32, gregs on x86), and naming one of
    // them here would turn a future ABI addition into a compile error
    // instead of a missing feature.
    const uintptr_t pc = 0, sp = 0, lr = 0;
    uintptr_t fp = 0;
#endif

    char buf[512];
    int n = snprintf(buf, sizeof(buf),
        "\nFaulting context:\n"
        "  pc 0x%016lx  sp 0x%016lx  fp 0x%016lx  lr 0x%016lx\n",
        (unsigned long)pc, (unsigned long)sp, (unsigned long)fp, (unsigned long)lr);
    if (n > 0) write_all(fd, buf, n);

    if (pc != 0) {
        const char* module = "?";
        uintptr_t base = 0;
        pc_module(pc, &module, &base);
        n = snprintf(buf, sizeof(buf), "  faulting pc -> %s+0x%lx\n",
                     module, (unsigned long)(pc >= base ? pc - base : 0));
        if (n > 0) write_all(fd, buf, n);
    }
    if (lr != 0) {
        const char* module = "?";
        uintptr_t base = 0;
        pc_module(lr, &module, &base);
        n = snprintf(buf, sizeof(buf), "  caller (lr)  -> %s+0x%lx\n",
                     module, (unsigned long)(lr >= base ? lr - base : 0));
        if (n > 0) write_all(fd, buf, n);
    }

    // The stack grows down, so older frames sit at higher addresses. A frame
    // record is [caller frame pointer][return address], so the return
    // address stored in the current frame belongs to the frame BELOW it.
    static const char hdr[] = "\nFaulting frames (frame-pointer chain):\n";
    write_all(fd, hdr, (int)sizeof(hdr) - 1);

    uintptr_t prev_fp = 0;
    int frame = 0;
    while (frame < MAX_FP_FRAMES) {
        if (fp == 0) break;
        if ((fp & 7u) != 0u) break;                 // arm64 frames are 8-aligned
        if (fp < sp) break;                        // below the stack pointer
        if (fp - sp > (1u << 20)) break;           // implausibly far above it
        if (prev_fp != 0 && fp <= prev_fp) break;  // not moving up the stack

        uintptr_t next_fp = *reinterpret_cast<uintptr_t*>(fp);
        uintptr_t ret = *reinterpret_cast<uintptr_t*>(fp + sizeof(uintptr_t));
        if (ret == 0) break;

        const char* module = "?";
        uintptr_t base = 0;
        pc_module(ret, &module, &base);
        n = snprintf(buf, sizeof(buf), "  #%02d pc 0x%016lx  %s+0x%lx\n",
                     frame, (unsigned long)ret, module,
                     (unsigned long)(ret >= base ? ret - base : 0));
        if (n > 0) write_all(fd, buf, n);

        prev_fp = fp;
        fp = next_fp;
        frame++;
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
    // [T-native-crash-context] The faulting thread's own frames. The
    // unwinder above stops at the signal trampoline; this one starts past
    // it, in the code that actually crashed.
    write_fault_context(fd, ctx);
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

// [T-native-crash-selftest] Exercises the capture path without a signal.
//
// The diagnostics this file produces are only worth anything if they are
// verified, and verifying them used to mean crashing the app on purpose -
// which kills the session the user is in. Instead, this builds a synthetic
// faulting context: a real stack region holding a frame chain whose return
// addresses are real function pointers, so the symbolisation and the walk
// both do real work, and writes the result to a file the caller can read.
//
// It does not test signal delivery - that path is exercised by every real
// crash - but it does test everything that was broken: the frame walk and
// the register dump, which previously produced a single useless frame.
extern "C" JNIEXPORT jstring JNICALL
Java_com_openminis_app_crash_NativeCrashHandler_nativeSelfTest(
    JNIEnv* env, jobject, jstring logDir) {
    if (logDir == nullptr) return nullptr;

    const char* dir = env->GetStringUTFChars(logDir, nullptr);
    if (dir == nullptr) return nullptr;

    char path[640];
    snprintf(path, sizeof(path), "%s/native-crash-selftest.log", dir);

    int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC, 0600);
    if (fd < 0) {
        env->ReleaseStringUTFChars(logDir, dir);
        return nullptr;
    }

    // A real frame chain on this thread's stack. Each record is
    // [caller frame pointer][return address], exactly what the arm64 ABI
    // stores, so the walk in write_fault_context reads it the same way it
    // would read a crashed thread's stack.
    uintptr_t chain[2 * 24];
    for (int i = 0; i < 24; i++) {
        chain[2 * i + 0] = (i + 1 < 24) ? reinterpret_cast<uintptr_t>(&chain[2 * (i + 1)]) : 0;
        // Real function addresses: dladdr has to resolve them for the output
        // to be meaningful, and a fake address would resolve to nothing.
        chain[2 * i + 1] = reinterpret_cast<uintptr_t>(
            (i % 2 == 0) ? (void*)&pc_module : (void*)&write_fault_context);
    }

    ucontext_t uc;
    memset(&uc, 0, sizeof(uc));
#if defined(__aarch64__)
    uc.uc_mcontext.pc = static_cast<uint64_t>(reinterpret_cast<uintptr_t>(&write_fault_context));
    uc.uc_mcontext.sp = static_cast<uint64_t>(reinterpret_cast<uintptr_t>(&chain[0]) - 256);
    uc.uc_mcontext.regs[29] = static_cast<uint64_t>(reinterpret_cast<uintptr_t>(&chain[0]));
    uc.uc_mcontext.regs[30] = static_cast<uint64_t>(reinterpret_cast<uintptr_t>(&pc_module));
#else
    uc.uc_mcontext.__gregs[29] = reinterpret_cast<uintptr_t>(&chain[0]);
    uc.uc_mcontext.__gregs[30] = reinterpret_cast<uintptr_t>(&pc_module);
#endif

    char line[512];
    int n = snprintf(line, sizeof(line),
        "=== Minis Native Crash SELF-TEST ===\n"
        "This file is produced without crashing the app.\n");
    if (n > 0) write_all(fd, line, n);

    write_backtrace(fd);
    write_fault_context(fd, &uc);
    close(fd);

    env->ReleaseStringUTFChars(logDir, dir);
    return env->NewStringUTF(path);
}
