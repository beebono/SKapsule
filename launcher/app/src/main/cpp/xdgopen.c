// xdgopen.c — minimal "xdg-open" replacement for the SK :game process.
//
// SK is a desktop game; its News/wiki/forum links call Runtime.exec("xdg-open
// <url>"). Android has no xdg-open, and a forked app process can't reach the
// intent system directly (SELinux blocks exec of /system/bin/am from
// untrusted_app). So this tiny stand-in just relays the URL to GameActivity
// over an abstract AF_UNIX socket; the Activity, sharing our :game process,
// fires an ACTION_VIEW intent and the real browser opens.
//
// Shipped as libxdgopen.so (see CMakeLists) so it lands in the APK's
// nativeLibraryDir — the only app dir we may exec from. A PATH symlink named
// "xdg-open" points here (created in GameActivity). Exit 0 on delivery.
#include <stddef.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

// Must byte-for-byte match the LocalServerSocket name on the Kotlin side. The
// leading NUL (sun_path[0]=='\0') selects the abstract namespace, so there is no
// filesystem entry and the bind/connect is same-uid only.
#define SOCK_NAME "com.skarm.launcher.xdgopen"

static int write_all(int fd, const char *buf, size_t len) {
    size_t off = 0;
    while (off < len) {
        ssize_t n = write(fd, buf + off, len - off);
        if (n <= 0) return -1;
        off += (size_t)n;
    }
    return 0;
}

int main(int argc, char **argv) {
    // argv[1] is the URL (Runtime.exec splits "xdg-open <url>" on whitespace).
    if (argc < 2 || argv[1][0] == '\0') return 2;

    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) return 1;

    struct sockaddr_un addr;
    memset(&addr, 0, sizeof addr);
    addr.sun_family = AF_UNIX;
    size_t namelen = strlen(SOCK_NAME);
    if (namelen + 1 > sizeof addr.sun_path) { close(fd); return 1; }
    memcpy(addr.sun_path + 1, SOCK_NAME, namelen);  // sun_path[0] stays NUL
    socklen_t addrlen = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + namelen);

    if (connect(fd, (struct sockaddr *)&addr, addrlen) < 0) { close(fd); return 1; }

    // Newline-terminate so the reader can pull one line per request.
    const char *url = argv[1];
    if (write_all(fd, url, strlen(url)) < 0 || write_all(fd, "\n", 1) < 0) {
        close(fd);
        return 1;
    }
    close(fd);
    return 0;
}
