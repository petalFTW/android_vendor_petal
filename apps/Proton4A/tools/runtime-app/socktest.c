/* Minimal Unix-socket + credentials round-trip to test what Wine needs.
 * Binds a socket, forks, connects with SO_PASSCRED, exchanges a byte, and
 * reports the recvmsg control-message length. Prints errno on failure. */
#define _GNU_SOURCE
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <unistd.h>

#include <netinet/in.h>
#include <arpa/inet.h>

int main(int argc, char **argv) {
    const char *path = argc > 1 ? argv[1] : "socktest.sock";
    {
        gid_t groups[32];
        int n = getgroups(32, groups);
        printf("uid=%d groups n=%d:", (int) getuid(), n);
        for (int i = 0; i < n; i++) printf(" %d", (int) groups[i]);
        printf("\n");
    }
    int tcp = socket(AF_INET, SOCK_STREAM, 0);
    if (tcp < 0) {
        printf("inet socket errno=%d\n", errno);
    } else {
        struct sockaddr_in address;
        memset(&address, 0, sizeof(address));
        address.sin_family = AF_INET;
        address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
        address.sin_port = htons(6000);
        if (bind(tcp, (void *) &address, sizeof(address)) < 0)
            printf("inet bind errno=%d\n", errno);
        else
            printf("inet bind ok\n");
    }
    unlink(path);
    int server = socket(AF_UNIX, SOCK_STREAM, 0);
    if (server < 0) { perror("socket"); return 1; }
    struct sockaddr_un address;
    memset(&address, 0, sizeof(address));
    address.sun_family = AF_UNIX;
    strncpy(address.sun_path, path, sizeof(address.sun_path) - 1);
    if (bind(server, (void *) &address, sizeof(address)) < 0) { perror("bind"); return 1; }
    if (listen(server, 5) < 0) { perror("listen"); return 1; }

    pid_t child = fork();
    if (child == 0) {
        int client = socket(AF_UNIX, SOCK_STREAM, 0);
        int one = 1;
        setsockopt(client, SOL_SOCKET, SO_PASSCRED, &one, sizeof(one));
        if (connect(client, (void *) &address, sizeof(address)) < 0) { perror("connect"); _exit(2); }
        char byte = 'x';
        if (write(client, &byte, 1) != 1) { perror("write"); _exit(3); }
        char reply;
        if (read(client, &reply, 1) != 1) { perror("child read"); _exit(4); }
        _exit(0);
    }

    int fd = accept(server, NULL, NULL);
    if (fd < 0) { perror("accept"); return 1; }
    struct ucred peer;
    socklen_t peer_len = sizeof(peer);
    if (getsockopt(fd, SOL_SOCKET, SO_PEERCRED, &peer, &peer_len) == 0) {
        printf("peer pid=%d uid=%d gid=%d\n", peer.pid, peer.uid, peer.gid);
    } else {
        printf("SO_PEERCRED failed errno=%d\n", errno);
    }
    int one = 1;
    setsockopt(fd, SOL_SOCKET, SO_PASSCRED, &one, sizeof(one));
    char byte;
    struct msghdr message;
    memset(&message, 0, sizeof(message));
    struct iovec iov = { &byte, 1 };
    message.msg_iov = &iov;
    message.msg_iovlen = 1;
    char control[256];
    message.msg_control = control;
    message.msg_controllen = sizeof(control);
    ssize_t n = recvmsg(fd, &message, 0);
    if (n < 0) { perror("recvmsg"); return 1; }
    printf("recvmsg=%zd controllen=%zu flags=%d\n",
           n, message.msg_controllen, message.msg_flags);
    write(fd, &byte, 1);
    int status = 0;
    waitpid(child, &status, 0);
    printf("child status=%d\n", WIFEXITED(status) ? WEXITSTATUS(status) : -1);
    return 0;
}
