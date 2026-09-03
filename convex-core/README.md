# Convex Core

[![Maven Central](https://img.shields.io/maven-central/v/world.convex/convex-core.svg?label=Maven%20Central)](https://search.maven.org/search?q=world.convex)
[![javadoc](https://javadoc.io/badge2/world.convex/convex-core/javadoc.svg)](https://javadoc.io/doc/world.convex/convex-core)

The foundational library for the [Convex](https://convex.world) decentralised network, providing the virtual machine, consensus algorithm, and core data structures.

## Features

- **Convex Virtual Machine (CVM)** - Lambda calculus-based execution environment for smart contracts
- **Convergent Proof of Stake (CPoS)** - Energy-efficient consensus algorithm
- **Immutable Data Structures** - Lattice-based structures optimised for decentralised systems
- **Etch Database** - High-performance persistent storage layer
- **Cryptography** - Ed25519 signatures and secure hashing utilities

## Installation

### Maven

```xml
<dependency>
    <groupId>world.convex</groupId>
    <artifactId>convex-core</artifactId>
    <version>0.8.16</version>
</dependency>
```

### Gradle

```groovy
implementation 'world.convex:convex-core:0.8.16'
```

## Usage

Use `convex-core` directly when you need to work with Convex data structures locally without network connectivity:

```java
import convex.core.data.*;
import convex.core.lang.*;

// Work with immutable data structures
AVector<CVMLong> vec = Vectors.of(1L, 2L, 3L);
AMap<Keyword, ACell> map = Maps.of(Keyword.create("name"), Strings.create("Alice"));

// Execute CVM code locally
Context ctx = Context.create(state, address);
ctx = ctx.eval(Reader.read("(+ 1 2 3)"));
ACell result = ctx.getResult();  // Returns 6
```

## Documentation

- [Javadoc API Reference](https://javadoc.io/doc/world.convex/convex-core)
- Design documents under [`docs/`](docs/), each an implementation companion to a
  CAD:
  - [Encoder design](docs/ENCODER_DESIGN.md) — encoding lengths, embedding, encoder hierarchy, multi-cell messages
  - [Consensus](docs/CONSENSUS.md) — determinism boundary, time handling, attack surfaces
  - [Network upgrades](docs/UPGRADE.md) — protocol versions, migrations, test state policy
  - [CNS implementation notes](docs/CNS.md) — registry layout and the planned v1 registry migration
  - [Etch v3](docs/ETCHv3.md) — header, durability and encryption overlay
  - [Etch garbage collection](docs/ETCH_GC.md) — online collection, cutover and recovery
  - [Lattice regions](docs/LATTICE_REGIONS.md) — the root lattice catalogue
  - [Lattice cursors](docs/LATTICE_CURSOR_DESIGN.md) — fork/sync, navigation, write boundaries
  - [Lattice applications](docs/LATTICE_APPLICATIONS.md) — component patterns over cursors
  - [CellExplorer](docs/CELL_EXPLORER_DESIGN.md) — budget-bounded JSON5 rendering
  - [JSON Schema](docs/JSON_SCHEMA.md) — ACell-native schema validation
- [Java Examples](https://github.com/Convex-Dev/convex/tree/develop/convex-core/src/test/java/convex/core/examples) (see `RawCVM.java` for running CVM code directly)
- [Convex Lisp Examples](https://github.com/Convex-Dev/convex/tree/develop/convex-core/src/test/resources/examples)
- [Convex Documentation](https://docs.convex.world)

## Building from Source

```bash
git clone https://github.com/Convex-Dev/convex.git
cd convex
./mvnw -B -T1C install -pl convex-core -am
```

**Note:** ANTLR4 generates parser code during the build. If your IDE shows errors, add `target/generated-sources/antlr4` as a source directory.

## License

Copyright 2018-2026 The Convex Foundation and Contributors

Code in convex-core is provided under the [Convex Public License](../LICENSE.md).

Contributors are encouraged to sign the Convex Contributor's Agreement, which may make contributors eligible for awards of Convex Coins.
