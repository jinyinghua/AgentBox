#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/syscall.h>
#include <ucontext.h>
#include <unistd.h>

#ifndef SYS_SECCOMP
#define SYS_SECCOMP 1
#endif

#ifndef __NR_pidfd_open
#define __NR_pidfd_open 434
#endif
#ifndef __NR_clone3
#define __NR_clone3 435
#endif
#ifndef __NR_close_range
#define __NR_close_range 436
#endif
#ifndef __NR_openat2
#define __NR_openat2 437
#endif
#ifndef __NR_pidfd_getfd
#define __NR_pidfd_getfd 438
#endif
#ifndef __NR_faccessat2
#define __NR_faccessat2 439
#endif
#ifndef __NR_process_madvise
#define __NR_process_madvise 440
#endif
#ifndef __NR_epoll_pwait2
#define __NR_epoll_pwait2 441
#endif
#ifndef __NR_mount_setattr
#define __NR_mount_setattr 442
#endif
#ifndef __NR_quotactl_fd
#define __NR_quotactl_fd 443
#endif
#ifndef __NR_landlock_create_ruleset
#define __NR_landlock_create_ruleset 444
#endif
#ifndef __NR_landlock_add_rule
#define __NR_landlock_add_rule 445
#endif
#ifndef __NR_landlock_restrict_self
#define __NR_landlock_restrict_self 446
#endif
#ifndef __NR_memfd_secret
#define __NR_memfd_secret 447
#endif
#ifndef __NR_process_mrelease
#define __NR_process_mrelease 448
#endif
#ifndef __NR_futex_waitv
#define __NR_futex_waitv 449
#endif
#ifndef __NR_fchmodat2
#define __NR_fchmodat2 452
#endif

enum guard_mode {
    GUARD_OFF = 0,
    GUARD_LOG = 1,
    GUARD_ENOSYS = 2,
};

static enum guard_mode guard_mode_value = GUARD_OFF;
static int guard_log_fd = -1;
static struct sigaction old_sigsys_action;
static volatile sig_atomic_t in_sigsys_handler = 0;
static unsigned char sigsys_altstack_mem[64 * 1024];
typedef long (*real_syscall_fn_t)(long number, ...);
static real_syscall_fn_t real_syscall_fn = NULL;

#if defined(__aarch64__)
static long raw_syscall6(long number, long a1, long a2, long a3, long a4, long a5, long a6) {
    register long x0 __asm__("x0") = a1;
    register long x1 __asm__("x1") = a2;
    register long x2 __asm__("x2") = a3;
    register long x3 __asm__("x3") = a4;
    register long x4 __asm__("x4") = a5;
    register long x5 __asm__("x5") = a6;
    register long x8 __asm__("x8") = number;
    __asm__ volatile("svc #0"
                     : "+r"(x0)
                     : "r"(x1), "r"(x2), "r"(x3), "r"(x4), "r"(x5), "r"(x8)
                     : "memory", "cc");
    return x0;
}

static long normalize_raw_syscall_return(long ret) {
    if (ret < 0 && ret >= -4095) {
        errno = (int)-ret;
        return -1;
    }
    return ret;
}
#endif

static void append_char(char *buf, size_t *pos, size_t cap, char c) {
    if (*pos + 1 < cap) buf[(*pos)++] = c;
}

static void append_str(char *buf, size_t *pos, size_t cap, const char *s) {
    if (!s) return;
    while (*s && *pos + 1 < cap) buf[(*pos)++] = *s++;
}

static void append_u64(char *buf, size_t *pos, size_t cap, uint64_t v) {
    char tmp[32];
    size_t n = 0;
    if (v == 0) {
        append_char(buf, pos, cap, '0');
        return;
    }
    while (v > 0 && n < sizeof(tmp)) {
        tmp[n++] = (char)('0' + (v % 10));
        v /= 10;
    }
    while (n > 0) append_char(buf, pos, cap, tmp[--n]);
}

static const char *syscall_name(int nr) {
    switch (nr) {
        case __NR_pidfd_open: return "pidfd_open";
        case __NR_clone3: return "clone3";
        case __NR_close_range: return "close_range";
        case __NR_openat2: return "openat2";
        case __NR_pidfd_getfd: return "pidfd_getfd";
        case __NR_faccessat2: return "faccessat2";
        case __NR_process_madvise: return "process_madvise";
        case __NR_epoll_pwait2: return "epoll_pwait2";
        case __NR_mount_setattr: return "mount_setattr";
        case __NR_quotactl_fd: return "quotactl_fd";
        case __NR_landlock_create_ruleset: return "landlock_create_ruleset";
        case __NR_landlock_add_rule: return "landlock_add_rule";
        case __NR_landlock_restrict_self: return "landlock_restrict_self";
        case __NR_memfd_secret: return "memfd_secret";
        case __NR_process_mrelease: return "process_mrelease";
        case __NR_futex_waitv: return "futex_waitv";
        case __NR_fchmodat2: return "fchmodat2";
        default: return "unknown";
    }
}

static int is_safe_enosys_syscall(int nr) {
    switch (nr) {
        case __NR_pidfd_open:
        case __NR_pidfd_getfd:
        case __NR_clone3:
        case __NR_close_range:
        case __NR_openat2:
        case __NR_faccessat2:
        case __NR_landlock_create_ruleset:
        case __NR_landlock_add_rule:
        case __NR_landlock_restrict_self:
        case __NR_memfd_secret:
        case __NR_process_mrelease:
        case __NR_fchmodat2:
            return 1;
        default:
            return 0;
    }
}

static void write_guard_log(int syscall_nr, int si_code, const char *action) {
    if (guard_log_fd < 0) return;
    char buf[256];
    size_t pos = 0;
    append_str(buf, &pos, sizeof(buf), "agentbox-syscall-guard: sigsys syscall=");
    append_u64(buf, &pos, sizeof(buf), (uint64_t)(syscall_nr < 0 ? 0 : syscall_nr));
    append_str(buf, &pos, sizeof(buf), " name=");
    append_str(buf, &pos, sizeof(buf), syscall_name(syscall_nr));
    append_str(buf, &pos, sizeof(buf), " si_code=");
    append_u64(buf, &pos, sizeof(buf), (uint64_t)(si_code < 0 ? 0 : si_code));
    append_str(buf, &pos, sizeof(buf), " action=");
    append_str(buf, &pos, sizeof(buf), action);
    append_char(buf, &pos, sizeof(buf), '\n');
    (void)write(guard_log_fd, buf, pos);
}

static void write_wrapper_log(long syscall_nr, const char *action) {
    if (guard_log_fd < 0) return;
    char buf[256];
    size_t pos = 0;
    append_str(buf, &pos, sizeof(buf), "agentbox-syscall-guard: wrapper syscall=");
    append_u64(buf, &pos, sizeof(buf), (uint64_t)(syscall_nr < 0 ? 0 : syscall_nr));
    append_str(buf, &pos, sizeof(buf), " name=");
    append_str(buf, &pos, sizeof(buf), syscall_name((int)syscall_nr));
    append_str(buf, &pos, sizeof(buf), " action=");
    append_str(buf, &pos, sizeof(buf), action);
    append_char(buf, &pos, sizeof(buf), '\n');
    (void)write(guard_log_fd, buf, pos);
}

long syscall(long number, ...) {
    if (guard_mode_value == GUARD_ENOSYS && is_safe_enosys_syscall((int)number)) {
        write_wrapper_log(number, "enosys");
        errno = ENOSYS;
        return -1;
    }

    va_list ap;
    long a1 = 0, a2 = 0, a3 = 0, a4 = 0, a5 = 0, a6 = 0;
    va_start(ap, number);
    a1 = va_arg(ap, long);
    a2 = va_arg(ap, long);
    a3 = va_arg(ap, long);
    a4 = va_arg(ap, long);
    a5 = va_arg(ap, long);
    a6 = va_arg(ap, long);
    va_end(ap);

    if (guard_mode_value == GUARD_LOG && is_safe_enosys_syscall((int)number)) {
        write_wrapper_log(number, "pass");
    }

#if defined(__aarch64__)
    return normalize_raw_syscall_return(raw_syscall6(number, a1, a2, a3, a4, a5, a6));
#else
    if (!real_syscall_fn) {
        real_syscall_fn = (real_syscall_fn_t)dlsym(RTLD_NEXT, "syscall");
        if (!real_syscall_fn) {
            errno = ENOSYS;
            return -1;
        }
    }
    return real_syscall_fn(number, a1, a2, a3, a4, a5, a6);
#endif
}

static void call_old_or_exit(int sig, siginfo_t *info, void *ucontext) {
    if (old_sigsys_action.sa_handler == SIG_DFL || old_sigsys_action.sa_handler == SIG_IGN ||
        old_sigsys_action.sa_handler == NULL) {
        _exit(128 + SIGSYS);
    }
    if (old_sigsys_action.sa_flags & SA_SIGINFO) {
        old_sigsys_action.sa_sigaction(sig, info, ucontext);
        return;
    }
    old_sigsys_action.sa_handler(sig);
}

static void sigsys_handler(int sig, siginfo_t *info, void *ucontext) {
    if (in_sigsys_handler) _exit(128 + SIGSYS);
    in_sigsys_handler = 1;

    int syscall_nr = info ? info->si_syscall : -1;
    int si_code = info ? info->si_code : 0;

    if (!info || si_code != SYS_SECCOMP) {
        write_guard_log(syscall_nr, si_code, "non-seccomp-chain");
        call_old_or_exit(sig, info, ucontext);
        return;
    }

    if (guard_mode_value == GUARD_ENOSYS && is_safe_enosys_syscall(syscall_nr)) {
#if defined(__aarch64__)
        ucontext_t *ctx = (ucontext_t *)ucontext;
        ctx->uc_mcontext.regs[0] = (uint64_t)(-ENOSYS);
        write_guard_log(syscall_nr, si_code, "enosys");
        in_sigsys_handler = 0;
        return;
#elif defined(__x86_64__)
        ucontext_t *ctx = (ucontext_t *)ucontext;
        ctx->uc_mcontext.gregs[REG_RAX] = (greg_t)(-ENOSYS);
        write_guard_log(syscall_nr, si_code, "enosys");
        in_sigsys_handler = 0;
        return;
#else
        write_guard_log(syscall_nr, si_code, "unsupported-arch-chain");
        call_old_or_exit(sig, info, ucontext);
        return;
#endif
    }

    write_guard_log(syscall_nr, si_code,
                    guard_mode_value == GUARD_LOG ? "log-chain" : "unknown-chain");
    call_old_or_exit(sig, info, ucontext);
}

__attribute__((constructor))
static void syscall_guard_init(void) {
    const char *mode = getenv("AGENTBOX_SYSCALL_GUARD");
    if (!mode || strcmp(mode, "off") == 0 || strcmp(mode, "0") == 0) {
        guard_mode_value = GUARD_OFF;
        return;
    }
    if (strcmp(mode, "log") == 0) guard_mode_value = GUARD_LOG;
    else if (strcmp(mode, "enosys") == 0 || strcmp(mode, "1") == 0) guard_mode_value = GUARD_ENOSYS;
    else guard_mode_value = GUARD_OFF;

    if (guard_mode_value == GUARD_OFF) return;

    const char *log_path = getenv("AGENTBOX_SYSCALL_GUARD_LOG");
    if (!log_path || log_path[0] == '\0') log_path = "/tmp/agentbox-syscall-guard.log";
    guard_log_fd = open(log_path, O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0644);
#if !defined(__aarch64__)
    real_syscall_fn = (real_syscall_fn_t)dlsym(RTLD_NEXT, "syscall");
#endif

    stack_t ss;
    memset(&ss, 0, sizeof(ss));
    ss.ss_sp = sigsys_altstack_mem;
    ss.ss_size = sizeof(sigsys_altstack_mem);
    ss.ss_flags = 0;
    (void)sigaltstack(&ss, NULL);

    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_sigaction = sigsys_handler;
    sigemptyset(&sa.sa_mask);
    sa.sa_flags = SA_SIGINFO | SA_ONSTACK;
    sigaction(SIGSYS, &sa, &old_sigsys_action);
}

__attribute__((destructor))
static void syscall_guard_fini(void) {
    if (guard_log_fd >= 0) close(guard_log_fd);
    guard_log_fd = -1;
}
