# Convex Integration

Build assembly and integration testing module for [Convex](https://convex.world).

## Purpose

This module produces the `convex.jar` distribution artifact - a self-contained executable JAR with all dependencies included for easy distribution and command-line usage.

## Build Output

After building, `convex.jar` is located at:

```
convex-integration/target/convex.jar
```

## Building

```bash
cd convex
mvn clean install
```

The JAR is created during the standard Maven build process.

## Running

```bash
# Launch desktop GUI
java -jar convex-integration/target/convex.jar desktop

# Use CLI commands
java -jar convex-integration/target/convex.jar --help
java -jar convex-integration/target/convex.jar local start
java -jar convex-integration/target/convex.jar key generate
```

See the [CLI documentation](../convex-cli/README.md) for full command reference.

## Integration Tests

Run live integration tests against a running network:

```bash
mvn verify -Pintegration-tests
```

## License

Copyright 2020-2025 The Convex Foundation and Contributors

Code in convex-integration is provided under the [Convex Public License](../LICENSE.md).
