---
name: build-convex
description: Build the Convex project from source. Use when a contributor wants to compile, test, or package Convex.
disable-model-invocation: true
allowed-tools: Bash
argument-hint: "[module] [--test]"
---

# Build Convex

Build the Convex Maven project. Requires Java 21+ (JDK 25 recommended) and Maven 3.7+.

Run every command from the repository root — that is already the working
directory, so do not `cd` anywhere first. Use `./mvnw` in preference to `mvn`
when the wrapper is present, so the build uses the pinned Maven version.

Parse `$ARGUMENTS` for two things:

- a **module** name (any argument starting `convex-`), e.g. `convex-core`
- the **`--test`** flag

## Full build, skipping tests

The default when no arguments are given:

```bash
./mvnw -B clean install -DskipTests
```

## Build a single module

```bash
./mvnw -B clean install -DskipTests -pl <module> -am
```

`-am` also builds the modules that one depends on.

## Build with tests

When `--test` is present, or the user asks for tests, drop `-DskipTests`. This
matches what CI runs, so it is the command to use before claiming work is done:

```bash
./mvnw -B clean install
```

For a single module's tests:

```bash
./mvnw -B test -pl <module> -am
```

Report the result — success or failure — and any errors, concisely. On failure,
quote the first real compilation or test error rather than the Maven summary.
