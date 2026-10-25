/* Proton4A SIGSYS softener, preloaded into Box64/Wine (glibc).
 *
 * Android's app seccomp policy kills the process with SIGSYS on a few
 * syscalls (set_robust_list, rseq, ...) that glibc issues during startup and
 * thread creation. Turn them into a normal -ENOSYS return, which glibc already
 * handles. Unlike the uid shim this does not touch getuid, so Wine's prefix
 * ownership checks stay correct.
 */
#define _GNU_SOURCE
#include <errno.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/syscall.h>
#include <ucontext.h>
#include <unistd.h>

static void sigsys_handler(int signal, siginfo_t *info, void *context) {
    const char *log = getenv("PROTON4A_SOFTEN_LOG");
    if (log) {
        FILE *f = fopen(log, "a");
        if (f) {
            fprintf(f, "SIGSYS syscall=%d code=%d pid=%d\n",
                    info->si_syscall, info->si_code, (int) getpid());
            fclose(f);
        }
    }
    ucontext_t *uc = context;
    uc->uc_mcontext.pc += 4;
    uc->uc_mcontext.regs[0] = (unsigned long) -ENOSYS;
}

static struct sigaction proton4a_sigsys = {
    .sa_sigaction = sigsys_handler,
    .sa_flags = SA_SIGINFO | SA_NODEFER,
};

int sigaction(int signum, const struct sigaction *act, struct sigaction *oldact) {
    if (signum == SIGSYS) {
        const char *log = getenv("PROTON4A_SOFTEN_LOG");
        if (log) {
            FILE *f = fopen(log, "a");
            if (f) { fprintf(f, "blocked sigaction SIGSYS pid=%d\n", (int) getpid()); fclose(f); }
        }
        if (oldact) *oldact = proton4a_sigsys;
        return 0;
    }
    return (int) syscall(__NR_rt_sigaction, signum, act, oldact, (long) 8);
}

sighandler_t signal(int signum, sighandler_t handler) {
    if (signum == SIGSYS) return SIG_DFL;
    struct sigaction action, old;
    memset(&action, 0, sizeof(action));
    action.sa_handler = handler;
    sigemptyset(&action.sa_mask);
    sigaction(signum, &action, &old);
    return old.sa_handler;
}

__attribute__((constructor)) static void install_handler(void) {
    const char *log = getenv("PROTON4A_SOFTEN_LOG");
    if (log) {
        FILE *f = fopen(log, "a");
        if (f) { fprintf(f, "soften loaded pid=%d ppid=%d\n", (int) getpid(), (int) getppid()); fclose(f); }
    }
    sigaction(SIGSYS, &proton4a_sigsys, NULL);
}
