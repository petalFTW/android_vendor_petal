/* Diagnostic for native Wine/Box32 timestamp calls in the Android app UID. */
#define _GNU_SOURCE
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/syscall.h>
#include <sys/time.h>
#include <time.h>
#include <unistd.h>

static void report(const char *name, int fd, const char *path,
        const struct timespec *times, int flags, int result, int saved) {
    if (result == 0 && (!path || !strstr(path, "chrome_100_percent.pak"))) return;
    const char *log = getenv("PROTON4A_TIME_LOG");
    if (!log) return;
    FILE *file = fopen(log, "a");
    if (!file) return;
    fprintf(file, "%s fd=%d path=%s flags=%d atime=%lld,%ld mtime=%lld,%ld result=%d errno=%d\n",
            name, fd, path ? path : "<null>", flags,
            times ? (long long) times[0].tv_sec : 0,
            times ? times[0].tv_nsec : 0,
            times ? (long long) times[1].tv_sec : 0,
            times ? times[1].tv_nsec : 0, result, saved);
    fclose(file);
}

int utimensat(int fd, const char *path, const struct timespec times[2], int flags) {
    int result = (int) syscall(__NR_utimensat, fd, path, times, flags);
    int saved = errno;
    report("utimensat", fd, path, times, flags, result, saved);
    errno = saved;
    return result;
}

int futimes(int fd, const struct timeval times[2]) {
    struct timespec converted[2];
    if (times) for (int i = 0; i < 2; i++) {
        converted[i].tv_sec = times[i].tv_sec;
        converted[i].tv_nsec = times[i].tv_usec * 1000;
    }
    int result = (int) syscall(__NR_utimensat, fd, NULL, times ? converted : NULL, 0);
    int saved = errno;
    report("futimes", fd, NULL, times ? converted : NULL, 0, result, saved);
    errno = saved;
    return result;
}
