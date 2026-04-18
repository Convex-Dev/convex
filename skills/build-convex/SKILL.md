---
name: build-convex
description: Build the Convex project from source. Use when a contributor wants to compile, test, or package Convex.
disable-model-invocation: true
allowed-tools: Bash
argument-hint: "[module] [--test]"
---

# Build Convex

Build the Convex Maven project. Requires Java 21+ and Maven 3.7+.

## Full build (skip tests)

```bash
cd C:/Users/mike_/git/convex && mvn clean install -DskipTests
```

## Build a specific module

```bash
cd C:/Users/mike_/git/convex && mvn clean install -DskipTests -pl $0 -am
```

The `-am` flag builds required dependencies too.

## Build and test

If `$ARGUMENTS` contains `--test` or the user asks to run tests:

```bash
cd C:/Users/mike_/git/convex && mvn clean install
```

For a specific module's tests:

```bash
cd C:/Users/mike_/git/convex && mvn test -pl $0
```

Report build result (success/failure) and any errors concisely.
