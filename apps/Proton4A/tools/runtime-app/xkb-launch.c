/* Tiny launcher symlinked as <app files>/xkbcomp.
 *
 * Xvfb runs <xkb_bindir>/xkbcomp; a script in app storage cannot be exec'd,
 * and the bundled xkbcomp is a glibc ELF that needs the bundled loader. This
 * launcher finds its own native-library directory, then re-execs the loader
 * with the shim preloaded and the real xkbcomp as the program.
 */
#include <errno.h>
#include <libgen.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

int main(int argc, char **argv) {
    char exe[4096];
    ssize_t length = readlink("/proc/self/exe", exe, sizeof(exe) - 1);
    if (length <= 0) { perror("readlink"); return 127; }
    exe[length] = '\0';
    char *dir = dirname(exe);

    char loader[4096], shim[4096], xkbcomp[4096];
    snprintf(loader, sizeof(loader), "%s/libp4aldr.so", dir);
    snprintf(shim, sizeof(shim), "%s/libuidshim.so", dir);
    snprintf(xkbcomp, sizeof(xkbcomp), "%s/libxkbcomp.so", dir);

    char **args = calloc((size_t) argc + 8, sizeof(char *));
    int i = 0;
    args[i++] = loader;
    args[i++] = "--library-path";
    args[i++] = dir;
    args[i++] = "--preload";
    args[i++] = shim;
    args[i++] = xkbcomp;
    for (int j = 1; j < argc; j++) args[i++] = argv[j];
    args[i] = NULL;

    const char *log = getenv("PROTON4A_XKB_LOG");
    if (log) {
        FILE *f = fopen(log, "a");
        if (f) {
            fprintf(f, "launch dir=%s\n", dir);
            for (int j = 0; j < i; j++) fprintf(f, "  arg[%d]=%s\n", j, args[j]);
            fflush(f);
            fclose(f);
        }
    }

    execv(loader, args);
    if (log) {
        FILE *f = fopen(log, "a");
        if (f) { fprintf(f, "execv failed: errno=%d\n", errno); fclose(f); }
    }
    return 127;
}
