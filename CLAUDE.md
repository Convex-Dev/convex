# Claude Code Guidelines for Convex

## Project Overview

Convex is a lattice-based decentralised network and execution platform built as a multi-module Maven project. The codebase implements a full-stack blockchain alternative with custom virtual machine, consensus layer, peer networking, and developer tooling.

## Build System

Maven 3.7+ multi-module project structure requiring Java 21+.

See BUILD.md for detailed build and release instructions.

Quick start:
```bash
mvn clean install          # Full build with local install
```

## Module Structure

9 Maven modules with clear separation of concerns:

| Module | Purpose |
|--------|---------|
| `convex-core` | CVM, consensus (CPoS), data structures, Etch database |
| `convex-peer` | Peer implementation, binary protocol, networking |
| `convex-cli` | Command-line tools and scripting |
| `convex-gui` | Swing desktop application |
| `convex-restapi` | HTTP REST API server |
| `convex-java` | Java client library |
| `convex-benchmarks` | JMH performance benchmarks |
| `convex-observer` | Network monitoring tools |
| `convex-integration` | Build assembly (produces convex.jar) |

Each module has its own README.md explaining specific functionality.

## Code Organization

### Package Naming
Standard reverse-domain convention: `convex.<module>.<feature>`

Examples:
- `convex.core.data` - Core data structures
- `convex.peer.Server` - Peer server implementation
- `convex.restapi.test` - REST API tests

### Test Structure
Tests follow JUnit 6 conventions with `src/test/java` mirroring `src/main/java` structure.

## Key Technologies

- **Netty** - Async networking
- **JUnit 6** - Testing
- **SLF4J** - Logging
- **Ed25519** - Cryptographic signatures
- **ANTLR4** - Parser generation (requires IDE source path configuration)

## Running Convex

See README.md for download links and detailed running instructions.

Quick reference:
```bash
java -jar convex.jar desktop   # Launch GUI
./convex local gui             # Local test network with GUI
```

## Development Workflow

### Branch Strategy
- `develop` - Active development (default branch)
- `master` - Release branch
- Feature branches as needed

### Release Process
See BUILD.md for complete release workflow.

### Known Issues
- ANTLR4 generated sources may need manual IDE source path configuration
- Add `target/generated-sources/antlr4` as source directory if needed

## Contributing

See README.md for contribution guidelines and license terms.

## Key Principles

- **Lattice technology** not linear blockchain
- **Lambda calculus VM** for functional smart contracts
- **Zero-energy consensus** (Convergent Proof of Stake)
- **Global state model** with atomic transactions
- **Millisecond consensus** latency
- **100% eco-friendly** by design

## Resources

See README.md for community links and project resources.
