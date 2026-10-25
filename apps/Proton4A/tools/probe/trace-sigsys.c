#define _GNU_SOURCE
#include <errno.h>
#include <stdint.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/ptrace.h>
#include <sys/types.h>
#include <sys/uio.h>
#include <sys/wait.h>
#include <unistd.h>

struct arm64_pt_regs {
    unsigned long long regs[31];
    unsigned long long sp;
    unsigned long long pc;
    unsigned long long pstate;
};

static void print_mapping(pid_t pid, unsigned long long address, const char *label) {
    char path[64], line[512];
    snprintf(path, sizeof(path), "/proc/%d/maps", pid);
    FILE *maps = fopen(path, "r");
    if (!maps) return;
    while (fgets(line, sizeof(line), maps)) {
        unsigned long start, end, offset;
        if (sscanf(line, "%lx-%lx %*4s %lx", &start, &end, &offset) == 3
                && address >= start && address < end) {
            fprintf(stderr, "%s: 0x%llx file_offset=0x%lx %s", label, address,
                    offset + address - start, line);
        }
    }
    fclose(maps);
}

/* Rootless diagnostic: trace a child in the same app SELinux domain. */
int main(int argc, char **argv) {
    if (argc < 2) return 2;
    pid_t child = fork();
    if (child == -1) { perror("fork"); return 2; }
    if (child == 0) {
        if (ptrace(PTRACE_TRACEME, 0, 0, 0) == -1) {
            perror("PTRACE_TRACEME");
            _exit(3);
        }
        raise(SIGSTOP);
        execv(argv[1], argv + 1);
        perror("execv");
        _exit(4);
    }
    int status;
    for (;;) {
        if (waitpid(child, &status, 0) == -1) { perror("waitpid"); return 3; }
        if (WIFEXITED(status)) {
            fprintf(stderr, "child exited %d\n", WEXITSTATUS(status));
            return 0;
        }
        if (WIFSIGNALED(status)) {
            fprintf(stderr, "child killed by signal %d\n", WTERMSIG(status));
            return 0;
        }
        if (!WIFSTOPPED(status)) continue;
        int signal = WSTOPSIG(status);
        if (signal == SIGSYS || signal == SIGSEGV) {
            siginfo_t info;
            if (ptrace(PTRACE_GETSIGINFO, child, 0, &info) == -1) {
                perror("PTRACE_GETSIGINFO");
            } else {
                if (signal == SIGSYS) {
                    fprintf(stderr, "SIGSYS: syscall=%d arch=0x%x code=%d addr=%p\n",
                            info.si_syscall, info.si_arch, info.si_code, info.si_call_addr);
                    print_mapping(child, (uintptr_t) info.si_call_addr, "signal");
                } else {
                    fprintf(stderr, "SIGSEGV: code=%d fault=%p\n", info.si_code, info.si_addr);
                    struct arm64_pt_regs regs;
                    struct iovec iov = { &regs, sizeof(regs) };
                    if (ptrace(PTRACE_GETREGSET, child, (void *) 1, &iov) == 0) {
                        fprintf(stderr, "PC=0x%llx LR=0x%llx SP=0x%llx\n",
                                regs.pc, regs.regs[30], regs.sp);
                        print_mapping(child, regs.pc, "pc");
                        print_mapping(child, regs.regs[30], "lr");
                    } else {
                        perror("PTRACE_GETREGSET");
                    }
                }
            }
            ptrace(PTRACE_KILL, child, 0, 0);
            waitpid(child, &status, 0);
            return 0;
        }
        if (signal != SIGSTOP && signal != SIGTRAP) {
            fprintf(stderr, "child stopped by signal %d\n", signal);
        }
        if (ptrace(PTRACE_CONT, child, 0, signal == SIGSTOP || signal == SIGTRAP ? 0 : signal)
                == -1) {
            perror("PTRACE_CONT");
            return 3;
        }
    }
}
