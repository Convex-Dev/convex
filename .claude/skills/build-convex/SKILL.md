---
name: build-convex
description: Build the Convex project from source. Use when a contributor wants to compile, test, or package Convex.
disable-model-invocation: true
allowed-tools: Bash
argument-hint: "[module]"
---

# Build Convex

Build the Convex Maven project. Requires Java 21+ (JDK 25 recommended). The
checked-in wrapper supplies the pinned Maven version.

Run every command from the repository root — that is already the working
directory, so do not `cd` anywhere first. Use `./mvnw` on Unix-like shells or
`.\mvnw.cmd` in PowerShell so the build uses the pinned Maven version.

Parse `$ARGUMENTS` for a **module** name (any argument starting `convex-`),
e.g. `convex-core`.

## Fast incremental build

The default when no arguments are given:

```bash
./mvnw -B -T1C test
```

## Build a single module

```bash
./mvnw -B -T1C test -pl <module> -am
```

`-am` also builds the modules that one depends on.

## Final verification

Use a clean, sequential build before claiming work is done:

```bash
./mvnw -B clean install
```

For a single module's tests:

```bash
./mvnw -B -T1C test -pl <module> -am
```

Report the result — success or failure — and any errors, concisely. On failure,
quote the first real compilation or test error rather than the Maven summary.
