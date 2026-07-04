# AgentBox syscall guard

Optional syscall guard for Android/proot Linux environments.

It is intentionally **not enabled globally**. Use it only for commands that are
known or suspected to fail with `Bad system call` / `SIGSYS`.

## Mechanisms

The guard provides two best-effort mechanisms:

1. an `LD_PRELOAD` `syscall()` wrapper for dynamically linked programs that call
   the libc `syscall` symbol;
2. a `SIGSYS` handler for seccomp traps that reach user space.

In `enosys` mode it returns `-ENOSYS` for a small allowlist of modern syscalls
that usually have fallback paths, such as:

- `clone3`
- `close_range`
- `openat2`
- `faccessat2`
- `pidfd_open`
- `pidfd_getfd`
- `landlock_*`
- `memfd_secret`
- `process_mrelease`
- `fchmodat2`

Unknown syscalls are logged and then chained to the previous handler/default
behavior. This avoids silently hiding unexpected security-relevant failures.

## Usage

First run a lightweight static preflight scan:

```sh
syscall-preflight /path/to/binary
```

Then test in log mode:

```sh
syscall-guard --log command args...
tail -f /tmp/agentbox-syscall-guard.log
```

If the log shows only known fallback-safe syscalls, try ENOSYS mode:

```sh
syscall-guard --enosys command args...
```

## Build inside the guest rootfs

The shared object is built on demand by the wrapper if a working compiler is
available. To build manually:

```sh
apk add --no-cache build-base linux-headers
build-syscall-guard
```

The output library is:

```sh
/usr/local/lib/agentbox/libsyscall_guard.so
```

## Limitations

- Static binaries and binaries using inline `svc #0` may bypass the LD_PRELOAD
  `syscall()` wrapper; they can only be helped if Android delivers a catchable
  `SIGSYS` trap.
- Some seccomp actions kill the process directly and cannot be handled in user
  space.
- Browsers may install their own SIGSYS/seccomp handlers. Prefer `--log` first
  and avoid enabling the guard globally for browser workloads.
