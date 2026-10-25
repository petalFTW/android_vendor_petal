/* Proton4A glibc shim, loaded with the bundled loader's --preload.
 *
 * 1. Reports uid 0 so Xvfb accepts -nolock (Android apps cannot use the X
 *    server lock file mechanism).
 * 2. Installs a SIGSYS handler that turns an Android-seccomp-trapped syscall
 *    into a normal -ENOSYS return instead of killing the process. glibc calls
 *    set_robust_list/rseq during startup and thread creation and already
 *    handles ENOSYS; Android traps them, which would otherwise abort. The
 *    handler is kept even if the program installs its own (Xorg would
 *    otherwise turn a recoverable ENOSYS into an abort).
 * 3. Repoints the X server's XkbBinDirectory at the app's writable home so it
 *    can run the bundled (glibc) xkbcomp via the loader launcher.
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <pwd.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <ucontext.h>
#include <unistd.h>

uid_t getuid(void) { return 0; }
uid_t geteuid(void) { return 0; }

/* Xorg thinks it is root for -nolock and tries to drop privileges before
 * invoking xkbcomp. The process is already confined to the app UID; Android
 * traps setuid/setgid even when invoked solely for that Xorg bookkeeping. */
int setuid(uid_t uid) { (void) uid; return 0; }
int setgid(gid_t gid) { (void) gid; return 0; }

/* Xvfb resolves uid 0 via getpwuid; give it a stable entry so it does not
 * dereference a NULL passwd (Android has no matching entry for the app). */
struct passwd *getpwuid(uid_t uid) {
    static struct passwd entry;
    static char name[] = "root";
    static char passwd[] = "x";
    static char dir[] = "/";
    static char shell[] = "/system/bin/sh";
    entry.pw_name = name;
    entry.pw_passwd = passwd;
    entry.pw_uid = 0;
    entry.pw_gid = 0;
    entry.pw_gecos = name;
    entry.pw_dir = dir;
    entry.pw_shell = shell;
    return &entry;
}

int getpwuid_r(uid_t uid, struct passwd *result, char *buffer, size_t buflen,
        struct passwd **out) {
    (void) buffer;
    (void) buflen;
    *out = getpwuid(uid);
    if (result) *result = *getpwuid(uid);
    return 0;
}

static void sigsys_handler(int signal, siginfo_t *info, void *context) {
    ucontext_t *uc = context;
    /* aarch64: skip the faulting svc and report the syscall as unsupported. */
    uc->uc_mcontext.pc += 4;
    uc->uc_mcontext.regs[0] = (unsigned long) -ENOSYS;
}

static struct sigaction proton4a_sigsys = {
    .sa_sigaction = sigsys_handler,
    .sa_flags = SA_SIGINFO | SA_NODEFER,
};

int sigaction(int signum, const struct sigaction *act, struct sigaction *oldact) {
    if (signum == SIGSYS) {
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
    sigaction(SIGSYS, &proton4a_sigsys, NULL);

    const char *home = getenv("PROTON4A_HOME");
    if (home) {
        char **bindir = (char **) dlsym(RTLD_DEFAULT, "XkbBinDirectory");
        if (bindir) *bindir = (char *) home;
    }
}
