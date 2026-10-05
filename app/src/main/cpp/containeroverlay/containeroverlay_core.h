#ifndef CONTAINEROVERLAY_CORE_H
#define CONTAINEROVERLAY_CORE_H

#include <dirent.h>
#include <stddef.h>
#include <stdint.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <time.h>

#define OVL_PATH_MAX 4096

#define OVL_RENAME_NOREPLACE (1u << 0)
#define OVL_RENAME_EXCHANGE  (1u << 1)

/** libc entry points used by the core; every file-system call the core makes goes through here. */
typedef struct ovl_ops {
    int (*open)(const char *path, int flags, mode_t mode);
    int (*close)(int fd);
    ssize_t (*read)(int fd, void *buf, size_t n);
    ssize_t (*write)(int fd, const void *buf, size_t n);
    int (*fstat)(int fd, struct stat *st);
    int (*stat)(const char *path, struct stat *st);
    int (*lstat)(const char *path, struct stat *st);
    int (*mkdir)(const char *path, mode_t mode);
    int (*rmdir)(const char *path);
    int (*unlink)(const char *path);
    int (*rename)(const char *from, const char *to);
    int (*rename_exchange)(const char *a, const char *b);
    /** rename that fails with EEXIST instead of replacing (RENAME_NOREPLACE); may be NULL. */
    int (*rename_noreplace)(const char *from, const char *to);
    ssize_t (*readlink)(const char *path, char *buf, size_t n);
    int (*symlink)(const char *target, const char *path);
    int (*link)(const char *from, const char *to);
    int (*fchmod)(int fd, mode_t mode);
    int (*futimens)(int fd, const struct timespec ts[2]);
    DIR *(*opendir)(const char *path);
    struct dirent *(*readdir)(DIR *d);
    int (*closedir)(DIR *d);
    ssize_t (*flistxattr)(int fd, char *list, size_t n);
    ssize_t (*fgetxattr)(int fd, const char *name, void *val, size_t n);
    int (*fsetxattr)(int fd, const char *name, const void *val, size_t n, int flags);
    char *(*getcwd)(char *buf, size_t n);
    /** Absolute path of an open fd (readlink of /proc/self/fd/N on Linux). Returns length or -1. */
    ssize_t (*fd_path)(int fd, char *buf, size_t n);
    /** Optional extra log destination (logcat on Android). */
    void (*log_sink)(const char *msg);
} ovl_ops;

enum { OVL_PASS = 0, OVL_IN = 1 };
enum { OVL_NONE = 0, OVL_UPPER = 1, OVL_LOWER = 2 };

/**
 * Result of path resolution.
 * OVL_IN: path is the overlay-relative path ("" for the root), textually normalised.
 * OVL_PASS: path (only filled by ovl_resolve_abs) is the absolute normalised path.
 */
typedef struct ovl_res {
    int kind;
    char path[OVL_PATH_MAX];
} ovl_res;

typedef struct ovl_dent {
    uint64_t ino;
    unsigned char type;
    unsigned char side;
    char *name;
} ovl_dent;

typedef struct ovl_snap {
    ovl_dent *e;
    size_t n;
    size_t cap;
} ovl_snap;

typedef struct ovl_dir ovl_dir;

/** Loop iterations spent in path scanning/normalisation since the last reset (for tests). */
extern unsigned long ovl_iters;

/** Configure the overlay. Returns 1 when active, 0 when disabled (bad/missing dirs). */
int ovl_init(const ovl_ops *ops, const char *upper, const char *lower, const char *aliases,
             int debug, const char *logpath);
int ovl_active(void);
void ovl_shutdown(void);
void ovl_log(const char *fmt, ...) __attribute__((format(printf, 1, 2)));

/** Cheap test: 0 means the absolute path can never be inside the overlay. */
int ovl_candidate(const char *path);

/** Lexically normalise path (relative to dirfd or cwd) into an absolute path. */
int ovl_normalize(int dirfd, const char *path, char *out, size_t outsz);

/** Returns OVL_PASS (use the caller's original arguments), OVL_IN, or -1 with errno. */
int ovl_resolve(int dirfd, const char *path, ovl_res *r);
/** Like ovl_resolve but r->path always holds an absolute path for PASS results. */
int ovl_resolve_abs(int dirfd, const char *path, ovl_res *r);

/** Side holding r (lstat semantics) or OVL_NONE with errno set; st and real may be NULL. */
int ovl_lookup(const ovl_res *r, struct stat *st, char *real);
void ovl_upper_path(const char *rel, char *out);
void ovl_lower_path(const char *rel, char *out);

/**
 * Follow a symlink in the last component of r (up to 8 hops). Updates r->path.
 * Returns 0 when the target is inside the overlay, 1 when it leaves it (outside gets the
 * absolute target), or -1 with errno.
 */
int ovl_follow(ovl_res *r, char *outside);

/** Real path for a read-only operation. follow: resolve a final symlink first. 0 or -1. */
int ovl_read_path(ovl_res *r, int follow, char *out);

/**
 * Prepare an open() of r with the caller's flags. Fills out with the path to open, sets
 * *created_new when ovl_finish_create must run after a successful open, and *is_dir when the
 * target is an existing directory. May update r->path when a final symlink is followed.
 */
int ovl_prepare_open(ovl_res *r, int flags, char *out, int *created_new, int *is_dir);
void ovl_finish_create(const ovl_res *r);
/** fopen mode string to open(2) flags. */
int ovl_fopen_flags(const char *mode);

/** Ensure r exists in upper (copy-up of file/symlink, or creation of the directory node). */
int ovl_materialize(ovl_res *r, int follow, int copy_data, char *path_out);

int ovl_unlink(const ovl_res *r);
int ovl_rmdir(const ovl_res *r);
int ovl_mkdir(const ovl_res *r, mode_t mode);
int ovl_symlink(const char *target, const ovl_res *r);
/** Either side may be OVL_PASS with an absolute path. */
int ovl_link(const ovl_res *from, const ovl_res *to);
int ovl_rename(const ovl_res *from, const ovl_res *to, unsigned flags);
/** realpath() replacement for overlay paths; lower paths come back in upper spelling. */
int ovl_realpath(ovl_res *r, char *out);

int ovl_snapshot(const char *rel, ovl_snap *s);
void ovl_snap_free(ovl_snap *s);

ovl_dir *ovl_opendir(const ovl_res *r, int fd);
int ovl_is_dir(const void *d);
struct dirent *ovl_readdir(ovl_dir *d);
int ovl_closedir(ovl_dir *d);
void ovl_rewinddir(ovl_dir *d);
long ovl_telldir(ovl_dir *d);
void ovl_seekdir(ovl_dir *d, long pos);
int ovl_dirfd(ovl_dir *d);

/**
 * If fd refers to a file in the lower layer, copy it up and write its upper path to out. If it
 * refers to a hard-linked (shared) upper file, give the path a private copy first.
 * Returns 1 (operate on out), 0 (pass through on the fd), -1 error.
 */
int ovl_fd_lower_copyup(int fd, char *out);

/**
 * dlsym hook decision. Returns hooks[i] when name == names[i], the lookup was not RTLD_NEXT, and
 * resolved is either the real libc function reals[i] or hooks[i]; otherwise returns resolved.
 */
void *ovl_dlsym_pick(int handle_is_next, const char *name, void *resolved, const char *const *names,
                     void *const *hooks, void *const *reals, size_t n);

#endif
