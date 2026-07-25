---
name: local-network
description: Run a local Convex test network for development. Use when testing changes against a live network, reproducing a peer issue, or when no remote network is configured.
allowed-tools: Bash
argument-hint: "[--count N] [--gui]"
---

# Run a Local Convex Network

A local network is the default way to exercise changes in this repository. It
needs no credentials, no remote host, and can be thrown away and recreated
freely. Prefer it over a remote network for anything that is not specifically
about remote behaviour.

Requires a built `convex.jar` — see the `build-convex` skill.

## Start a Temporary Network

```bash
java -jar convex.jar local start
```

Starts a throwaway test network. State is not preserved between runs, which is
what you want for testing.

Useful options:

| Option | Effect |
|--------|--------|
| `--count N` | Number of peers to launch |
| `--ports ...` | Specific peer ports (default: assigned automatically) |
| `--api-port N` | Port for the REST API |
| `--norest` | Do not start the REST server |
| `--no-tray` | No system tray icon |
| `--protocol-version N` | Pin the protocol version |

## Start with the Peer Manager GUI

```bash
java -jar convex.jar local gui
```

Launches the same local network under the peer manager GUI — useful for
watching consensus and inspecting peer state visually.

## Talking to It

Once running, point the client commands at the local peer:

```bash
java -jar convex.jar client query --host localhost --port <PORT> '(balance #12)'
java -jar convex.jar client status --host localhost --port <PORT>
```

## Notes for Tests

Do **not** start a network from a JUnit test by shelling out to the CLI. Tests
construct peers in-process; see the existing tests in `convex-peer`. The rules
in `AGENTS.md` apply — bind port `0` and read back the assigned port, and wait
on real signals rather than sleeping.

Stop the network when finished — it holds ports and a temporary store.
