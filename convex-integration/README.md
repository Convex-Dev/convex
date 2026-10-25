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
./mvnw -B -T1C install
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

`SharedApplicationsExample` is a runnable social + P2P + DLFS example in
`src/test/java/convex/lattice`. It registers P2P infrastructure once, composes
social and DLFS as independent region components, then replicates between two
local nodes. Incoming data reconciles with pending domain edits; each application
chooses when to publish its region. The example also shows streamed file access
through the standard Java filesystem API.

After `./mvnw -B clean install`, run it on Windows with:

```powershell
java -cp "convex-integration/target/test-classes;convex-integration/target/convex.jar" convex.lattice.SharedApplicationsExample
```

On Linux/macOS, use `:` instead of `;` between the classpath entries.
`SharedApplicationsTest` tests this example without an external network.

Run live integration tests against a running network:

```bash
./mvnw -B verify -Pintegration-tests
```

## License

Copyright 2020-2025 The Convex Foundation and Contributors

Code in convex-integration is provided under the [Convex Public License](../LICENSE.md).
