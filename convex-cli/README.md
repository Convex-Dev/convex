# Convex CLI

Command-line interface for the Convex decentralized network. Query state, execute transactions, manage keys, run local test networks, and operate production peers.

## Quick Start

```bash
# Check installation
convex --version

# Get help
convex --help

# Start a local test network
convex local start
```

## Installation

### Prerequisites

Java 21+ is required. Download from [Oracle JDK](https://www.oracle.com/java/technologies/downloads/) or use your package manager.

### Get the JAR

Download `convex.jar` from the [releases page](https://github.com/Convex-Dev/convex/releases) or build from source (see main [README](../README.md)).

### Create a Wrapper Script

**Linux/macOS** - Create `~/bin/convex`:
```bash
#!/bin/bash
java -jar /path/to/convex.jar "$@"
```
Then: `chmod +x ~/bin/convex`

**Windows PowerShell** - Add to your `$PROFILE`:
```powershell
function convex { java -jar C:\path\to\convex.jar $args }
```

## Use Case 1: Local Test Network

Spin up a local Convex network for development and testing. This creates a fresh network with genesis accounts you can use immediately.

```bash
# Start local network with GUI
convex local start

# Start without GUI (headless)
convex local start --gui=false
```

The local network includes:
- A single peer running on localhost:18888
- REST API on localhost:8080
- Pre-funded genesis accounts (#11, #12) for testing
- Faucet enabled for easy account funding

Test your connection:
```bash
convex status --host localhost
```

## Use Case 2: Key Management

Convex uses Ed25519 cryptographic keys for signing transactions. Keys are stored encrypted in a PKCS#12 keystore (default: `~/.convex/keystore.pfx`).

### Generate a New Key

```bash
convex key generate
```

This outputs:
1. A BIP39 mnemonic phrase (12 words) - **back this up securely!**
2. The public key (32-byte hex)

You'll be prompted for:
- BIP39 passphrase (used in key derivation)
- Key encryption password (protects the key in storage)

Example output:
```
BIP39 mnemonic generated with 12 words:
evidence expand family claw crack dawn name salmon resource leg once curious
Generated key pair with public key: 0x021efb3ff24898dffb30c9c7e490e86b2d0cb7a87c974a51894354532ff4670f
```

### List Keys

```bash
convex key list
```

### Import from Mnemonic

Recover a key from its BIP39 mnemonic:
```bash
convex key import --type=bip39 --text='evidence expand family claw crack dawn name salmon resource leg once curious'
```

### Export a Key

Export as seed (hex) or mnemonic for backup:
```bash
convex key export --key 021efb --type=seed
convex key export --key 021efb --type=bip39
```

### Environment Variables

Avoid repeated password prompts:
```bash
export CONVEX_KEY=021efb3ff2...        # Public key prefix
export CONVEX_KEY_PASSWORD=secret       # Key encryption password
export CONVEX_KEYSTORE=~/my-keys.pfx    # Custom keystore location
```

## Use Case 3: Query and Transact

Interact with any Convex network: read state with queries (free), modify state with transactions (requires signed account).

### Queries (Free, No Signature Required)

```bash
# Check balance of account #11
convex query "*balance*" -a 11

# Read any account's balance
convex query "(balance #1234)"

# Get account information
convex account info 11

# Check network status
convex status
```

By default, commands connect to the public network (`peer.convex.live`). For local development:
```bash
convex query "*balance*" -a 11 --host localhost
```

### Transactions (Require Key)

Transactions modify state and cost Convex coins. You need:
- An account address (`-a`)
- The account's key (`--key`)

```bash
# Transfer coins
convex transact "(transfer #1234 1000000)" -a 11 --key 021efb

# Deploy a simple contract
convex transact "(def my-value 42)" -a 11 --key 021efb

# Call a function
convex transact "(call #5678 (my-function arg1 arg2))" -a 11 --key 021efb
```

### Create an Account

**On local network (faucet enabled):**
```bash
convex account create --faucet --host localhost
```

**On production (requires existing funded account):**
```bash
convex account create -a 11 --key 021efb
```

### Fund an Account (Local/Test Networks Only)

```bash
convex account fund -a 1234 1000000000 --host localhost
```

Note: The faucet is disabled on production networks like Protonet.

## Use Case 4: Running a Production Peer

Operate a peer node that participates in Convex consensus. Peers validate transactions, maintain state, and serve client requests.

### 1. Generate Peer Key

```bash
convex key generate
# Note the public key, e.g., 7e66429ca...
```

### 2. Create Genesis or Join Existing Network

**Option A: Start a new network (genesis peer):**
```bash
convex peer genesis --peer-key 7e66429ca
```

**Option B: Join an existing network:**

First, create your peer's on-chain account and stake:
```bash
# Create peer account on the network
convex transact "(create-peer 0x7e66429ca... 1000000000000)" -a 11 --key 021efb
```

### 3. Start the Peer

```bash
convex peer start --peer-key 7e66429ca --port 18888
```

### Peer Options

```bash
convex peer start \
  --peer-key 7e66429ca \
  --port 18888 \
  --bind 0.0.0.0 \
  --api-port 8080 \
  --state /var/convex/state
```

| Option | Description |
|--------|-------------|
| `--peer-key` | Public key of peer's key pair |
| `--port` | Binary protocol port (default: 18888) |
| `--bind` | Bind address (default: localhost) |
| `--api-port` | REST API port (default: 8080) |
| `--state` | Directory for persistent state |

## Global Options

These options work with most commands:

| Option | Environment Variable | Description |
|--------|---------------------|-------------|
| `--host` | `CONVEX_HOST` | Peer hostname (default: peer.convex.live) |
| `--port` | `CONVEX_PORT` | Peer port (default: 18888) |
| `-a, --address` | `CONVEX_ADDRESS` | Account address |
| `--key` | `CONVEX_KEY` | Public key (prefix) in keystore |
| `--keypass` | `CONVEX_KEY_PASSWORD` | Key encryption password |
| `--keystore` | `CONVEX_KEYSTORE` | Keystore file path |
| `-n, --noninteractive` | - | Disable interactive prompts |
| `-S, --strict-security` | - | Require explicit passwords |
| `-v, --verbose` | `CONVEX_VERBOSE_LEVEL` | Verbosity (0-5) |

## Command Reference

```
convex
  account     Account management
    create    Create a new account
    info      Get account information
    balance   Check account balance
    fund      Request faucet funds (test networks)

  key         Key management
    generate  Generate new key pair
    list      List keys in keystore
    import    Import key from mnemonic/seed
    export    Export key

  query       Execute read-only query
  transact    Execute transaction
  status      Check peer/network status

  local       Local test network
    start     Start local network

  peer        Peer operations
    start     Start a peer node
    genesis   Create genesis peer
    create    Create peer on network

  desktop     Launch GUI application

  help        Show help for commands
```

## Exit Codes

| Code | Meaning |
|------|---------|
| 0 | Success |
| 1 | General error |
| 64 | Usage error (bad arguments) |
| 65 | Data error (invalid input) |
| 68 | No host (cannot connect) |
| 75 | Temporary failure (timeout) |
| 77 | Permission denied |

## License

Copyright 2021-2025 The Convex Foundation and Contributors

Code in `convex-cli` is provided under the Convex Public License.
