---
name: peer
description: Operate a Convex peer — create, start, list, back up, or instantiate a network genesis. Use when running a peer against a network or setting up a new network.
allowed-tools: Bash
argument-hint: "[create|start|list|backup|genesis]"
---

# Running a Convex Peer

A peer participates in consensus (CPoS), holds state in an Etch store, and
optionally serves the REST API.

For local development and testing you almost always want the `local-network`
skill instead — it starts a throwaway network in one command. Use this skill
when running a peer against a real network, or setting up a new one.

`peer` accepts `-c` / `--config` for a configuration file.

## Joining an Existing Network

```bash
java -jar convex.jar peer create --host <existing-peer>
```

Configures and creates a peer against a running network. Needs an existing peer
to bootstrap from and a valid peer controller account; generates a new peer key
unless one is supplied.

Then start it:

```bash
java -jar convex.jar peer start
```

Useful `start` options:

| Option | Effect |
|--------|--------|
| `--peer-port N` | Port for the peer protocol |
| `--api-port N` | Port for the REST API |
| `--url` / `--base-url` | Externally visible URL for this peer |
| `-a`, `--address` | Peer controller account |
| `--genesis` | Start from a genesis state |
| `--reset` | Reset stored state before starting |
| `--recalc` | Recalculate state from a given block position |
| `--norest` | Do not start the REST server |
| `--no-tray` | No system tray icon |
| `--protocol-version N` | Pin the protocol version |

`--reset` discards local peer state. Confirm before using it on anything that
is not disposable.

## Creating a New Network

```bash
java -jar convex.jar peer genesis
```

Instantiates a new Convex network — a genesis state with this peer as the
first participant. `--governance-key` sets the governance key;
`--protocol-version` pins the protocol version.

This creates a *new network*, not a connection to an existing one. Do not run
it when the intent was to join Protonet or a testnet.

## Managing

```bash
java -jar convex.jar peer list                    # peers in the current store
java -jar convex.jar peer backup -o <file>        # back up stored peer data
```

Back up before any operation that resets or migrates peer state. See the `etch`
skill for working on the underlying store directly.

## Keys

Peer keys are separate from account keys: `--peer-key` and `--peer-keypass`
select the peer's key from the keystore. The peer controller account is set
with `-a` / `--address`.
