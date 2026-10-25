/* Minimal aarch64 syscall tracer (Bionic static) for differential debugging.
 *
 * Forks and traces a child, following fork/vfork/clone, and logs socket- and
 * process-related syscalls with their arguments and return values. A syscall
 * ENTER with no matching EXIT before a signal death names a syscall that the
 * sandbox rejected. Trapped syscalls (SIGSYS) are logged with their number.
 *
 * Usage: systrace <program> [args...]   (log path from SYSTRACE_LOG)
 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ptrace.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <sys/uio.h>
#include <sys/wait.h>
#include <unistd.h>

struct regs {
    unsigned long long r[31];
    unsigned long long sp, pc, pstate;
};

#define MAXP 512
static pid_t pids[MAXP];
static int in_syscall[MAXP];
static long last_nr[MAXP];
static int np;
static FILE *logf;
static int signal_only;
static int file_times;

static void resume(pid_t pid, int signal) {
    ptrace(signal_only ? PTRACE_CONT : PTRACE_SYSCALL, pid, 0,
            (void *) (long) signal);
}

static int interesting(long n) {
    if (file_times) return n == 34 || n == 88 || n == 412;
    switch (n) {
        case 198: case 199: case 200: case 201: case 202: case 203:
        case 204: case 205: case 206: case 207: case 208: case 209:
        case 210: case 211: case 212: case 242: case 243: case 269:
        case 220: case 435: case 221: case 260: /* wait4 */
            return 1;
        default:
            return 0;
    }
}

static void log_time_path(pid_t pid, unsigned long long pointer,
                          unsigned long long times) {
    char name[257];
    size_t i = 0;
    while (pointer && i < sizeof(name) - sizeof(long)) {
        errno = 0;
        long word = ptrace(PTRACE_PEEKDATA, pid, (void *) (pointer + i), 0);
        if (word == -1 && errno) break;
        memcpy(name + i, &word, sizeof(word));
        if (memchr(name + i, 0, sizeof(word))) break;
        i += sizeof(word);
    }
    name[sizeof(name) - 1] = 0;
    fprintf(logf, " path=%s", pointer ? name : "<null>");
    if (times) {
        fprintf(logf, " ts=");
        for (int j = 0; j < 4; j++) {
            errno = 0;
            long value = ptrace(PTRACE_PEEKDATA, pid, (void *) (times + j * 8), 0);
            if (value == -1 && errno) break;
            fprintf(logf, "%s%ld", j ? "," : "", value);
        }
    }
    fputc('\n', logf);
}

struct syscall_info {
    unsigned char op, pad[3];
    unsigned int arch;
    unsigned long long ip, sp;
    union {
        struct { unsigned long long nr, args[6]; } entry;
        struct { long long ret; unsigned char error; } exit;
    } u;
};

static int index_of(pid_t pid) {
    for (int i = 0; i < np; i++) if (pids[i] == pid) return i;
    if (np < MAXP) { pids[np] = pid; in_syscall[np] = 0; return np++; }
    return -1;
}

int main(int argc, char **argv) {
    if (argc < 2) { fprintf(stderr, "usage: systrace prog args...\n"); return 2; }
    const char *path = getenv("SYSTRACE_LOG");
    signal_only = getenv("SYSTRACE_SIGNALS_ONLY") != NULL;
    file_times = getenv("SYSTRACE_FILE_TIMES") != NULL;
    logf = fopen(path ? path : "systrace.log", "w");
    if (!logf) { perror("log"); return 2; }

    pid_t root = fork();
    if (root == 0) {
        ptrace(PTRACE_TRACEME, 0, 0, 0);
        raise(SIGSTOP);
        execvp(argv[1], &argv[1]);
        _exit(127);
    }

    int status;
    waitpid(root, &status, 0);
    ptrace(PTRACE_SETOPTIONS, root, 0,
           (void *) (PTRACE_O_TRACEFORK | PTRACE_O_TRACEVFORK | PTRACE_O_TRACECLONE |
                     PTRACE_O_TRACEEXEC | PTRACE_O_TRACESYSGOOD | PTRACE_O_EXITKILL));
    index_of(root);
    resume(root, 0);

    for (;;) {
        pid_t pid = waitpid(-1, &status, __WALL);
        if (pid < 0) { if (errno == ECHILD) break; continue; }
        int idx = index_of(pid);

        if (WIFEXITED(status)) {
            fprintf(logf, "%d EXITED code=%d\n", pid, WEXITSTATUS(status));
            continue;
        }
        if (WIFSIGNALED(status)) {
            fprintf(logf, "%d KILLED signal=%d%s\n", pid, WTERMSIG(status),
                    in_syscall[idx] ? " (during syscall ENTER)" : "");
            continue;
        }

        int sig = WSTOPSIG(status);

        if (sig == (SIGTRAP | 0x80)) {
            if (file_times) {
                struct syscall_info info;
                memset(&info, 0, sizeof(info));
                long size = ptrace(0x420e, pid,
                                   (void *) sizeof(info), &info);
                if (size < 0) {
                    fprintf(logf, "%d GET_SYSCALL_INFO errno=%d\n", pid, errno);
                } else if (info.op == 1) {
                    long nr = info.u.entry.nr;
                    last_nr[idx] = nr;
                    if (interesting(nr)) {
                        fprintf(logf, "%d ENTER %ld fd=%lld pathptr=%llx times=%llx",
                                pid, nr, (long long) info.u.entry.args[0],
                                info.u.entry.args[1], info.u.entry.args[2]);
                        log_time_path(pid, info.u.entry.args[1],
                                nr == 34 ? 0 : info.u.entry.args[2]);
                    }
                } else if (info.op == 2 && interesting(last_nr[idx])) {
                    fprintf(logf, "%d EXIT %ld ret=%lld error=%d\n", pid, last_nr[idx],
                            info.u.exit.ret, info.u.exit.error);
                }
                fflush(logf);
                resume(pid, 0);
                continue;
            }
            struct regs regs;
            struct iovec iov = { &regs, sizeof(regs) };
            if (ptrace(PTRACE_GETREGSET, pid, (void *) 1, &iov) == 0) {
                long nr = (long) regs.r[8];
                if (!in_syscall[idx]) {
                    if (interesting(nr)) {
                        fprintf(logf, "%d ENTER %ld a0=%llx a1=%llx a2=%llx a3=%llx",
                                pid, nr, regs.r[0], regs.r[1], regs.r[2], regs.r[3]);
                        if (file_times && nr == 88) log_time_path(pid, regs.r[1], regs.r[2]);
                        else fputc('\n', logf);
                    }
                    in_syscall[idx] = 1;
                } else {
                    if (interesting(nr))
                        fprintf(logf, "%d EXIT  %ld ret=%lld errno=%d\n",
                                pid, nr, (long long) regs.r[0],
                                (regs.r[0] > (unsigned long long) -4096) ? -(int) regs.r[0] : 0);
                    in_syscall[idx] = 0;
                }
                fflush(logf);
            }
            resume(pid, 0);
        } else if (sig == SIGSYS) {
            siginfo_t si;
            if (ptrace(PTRACE_GETSIGINFO, pid, 0, &si) == 0) {
                fprintf(logf, "%d SIGSYS syscall=%d code=%d\n", pid, si.si_syscall, si.si_code);
                if (signal_only) {
                    struct regs regs;
                    struct iovec iov = { &regs, sizeof(regs) };
                    if (ptrace(PTRACE_GETREGSET, pid, (void *) 1, &iov) == 0) {
                        regs.r[0] = (unsigned long long) -ENOSYS;
                        ptrace(PTRACE_SETREGSET, pid, (void *) 1, &iov);
                    }
                }
            }
            fflush(logf);
            resume(pid, 0);
        } else if (sig == SIGSEGV || sig == SIGBUS) {
            struct regs regs;
            struct iovec iov = { &regs, sizeof(regs) };
            if (ptrace(PTRACE_GETREGSET, pid, (void *) 1, &iov) == 0) {
                siginfo_t si;
                memset(&si, 0, sizeof(si));
                ptrace(PTRACE_GETSIGINFO, pid, 0, &si);
                fprintf(logf, "%d SIGNAL sg=%d fault=%p pc=0x%llx lr=0x%llx x0=0x%llx x1=0x%llx fp=0x%llx sp=0x%llx\n",
                        pid, sig, si.si_addr, regs.pc, regs.r[30], regs.r[0],
                        regs.r[1], regs.r[29], regs.sp);
                char map[512];
                snprintf(map, sizeof(map), "/proc/%d/maps", pid);
                FILE *maps = fopen(map, "r");
                if (maps) {
                    char line[512];
                    while (fgets(line, sizeof(line), maps)) {
                        unsigned long s, e, o;
                        if (sscanf(line, "%lx-%lx %*4s %lx", &s, &e, &o) == 3) {
                            if (regs.pc >= s && regs.pc < e)
                                fprintf(logf, "  pc file_offset=0x%llx %s",
                                        regs.pc - s + o, line);
                            if (regs.r[30] >= s && regs.r[30] < e)
                                fprintf(logf, "  lr file_offset=0x%llx %s",
                                        regs.r[30] - s + o, line);
                        }
                    }
                    fclose(maps);
                }
            }
            fflush(logf);
            resume(pid, sig);
        } else if (sig == SIGTRAP) {
            unsigned long msg = 0;
            ptrace(PTRACE_GETEVENTMSG, pid, 0, &msg);
            if (msg) index_of((pid_t) msg);
            in_syscall[idx] = 0;
            resume(pid, 0);
        } else if (sig == SIGSTOP || sig == SIGCONT) {
            resume(pid, 0);
        } else {
            resume(pid, sig);
        }
    }

    fclose(logf);
    return 0;
}
