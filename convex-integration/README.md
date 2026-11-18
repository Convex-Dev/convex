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

Client correctness tests run locally in the ordinary test phase on Windows,
Linux and macOS. No public peer, faucet or network credentials are required:

```bash
./mvnw -B -T1C test -pl convex-integration -am
```

`ConvexJSONTest` and `RemoteBinaryClientTest` share one in-memory peer and REST
server through a JUnit root-store extension. Both ports are OS-assigned. Each
invocation owns its client and account, allowing concurrent JSON, native HTTP and
TCP tests without transaction sequence interference. Tests cover queries and
errors, account creation and funding, asynchronous calls, repeated signed
transactions and status decoding. Cleanup closes REST, the peer and its store at
the end of the test run. The same concurrency settings apply in Eclipse.


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

## Deployment smoke checks

Deployment checks are separate from client correctness. The `deployment-smoke`
profile selects `DeploymentSmokeIT`, which only queries the specified endpoint
and validates its status response. It creates no accounts and sends no
transactions. It has no default public URL; without `convex.smoke.url`, the
checks are disabled, including when Eclipse discovers the class.

```bash
./mvnw -B verify -pl convex-integration -am -Pdeployment-smoke '-Dconvex.smoke.url=https://your-peer.example'
```

Use `.\mvnw.cmd` in PowerShell and keep the property argument quoted. This
replaces the former `integration-tests` profile; normal local correctness tests
still run when the smoke profile is enabled.

## License

Copyright 2020-2025 The Convex Foundation and Contributors

Code in convex-integration is provided under the [Convex Public License](../LICENSE.md).
