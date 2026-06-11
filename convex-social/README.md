# Convex Social

[![Maven Central](https://img.shields.io/maven-central/v/world.convex/convex-social.svg?label=Maven%20Central)](https://search.maven.org/search?q=world.convex)
[![javadoc](https://javadoc.io/badge2/world.convex/convex-social/javadoc.svg)](https://javadoc.io/doc/world.convex/convex-social)

A lattice-based, peer-to-peer social network built on Convex. Each user owns a
cryptographically signed feed that only they can write to. Nodes selectively replicate
feeds based on follow relationships, and timelines are built by merging followed feeds
by timestamp.

## Overview

The social lattice composes standard Convex lattice primitives into a two-level
structure:

```
SocialLattice (per-user, signed by owner):
  :feed    → IndexLattice<Blob, ACell>   8-byte timestamp keys, last-writer-wins per entry
  :profile → LWWLattice                  display name, bio, avatar, etc.
  :follows → MapLattice<ACell, ACell>    followed key → {active, timestamp}
```

Each user's data is wrapped in `SignedData` via an `OwnerLattice`, so only the owner's
Ed25519 key can sign updates and foreign data is rejected during merge. The base layer
is intentionally minimal and extensible — easy to layer applications and UI on top.

## Features

- Owner-signed feeds — only the holder of the key can post
- Follow-based selective replication between nodes
- Timelines constructed by merging followed feeds by timestamp
- Conflict-free merge of independently updated replicas (CRDT semantics)
- Cursor-based Java API with `fork()` / `sync()` for batched operations

## Installation

### Maven

```xml
<dependency>
    <groupId>world.convex</groupId>
    <artifactId>convex-social</artifactId>
    <version>0.8.4</version>
</dependency>
```

### Gradle

```groovy
implementation 'world.convex:convex-social:0.8.4'
```

## Usage

```java
import convex.social.Social;
import convex.core.crypto.AKeyPair;

AKeyPair keyPair = AKeyPair.generate();

// Standalone instance with its own cursor
Social social = Social.create(keyPair);
social.user(keyPair.getAccountKey()).feed().post("Hello!");

// Fork for batch operations, then sync back
Social forked = social.fork();
forked.user(keyPair.getAccountKey()).feed().post("Post 1");
forked.user(keyPair.getAccountKey()).feed().post("Post 2");
forked.sync();
```

### Node integration

The social lattice is **not** part of convex-core's `Lattice.ROOT`. Nodes opt in by
composing it into their root lattice:

```java
KeyedLattice root = Lattice.ROOT.addLattice(Social.KEY_SOCIAL, Social.SOCIAL_LATTICE);
```

A `Social` instance can then be connected to a node's root cursor so that writes
propagate up for lattice push/pull:

```java
Social social = Social.connect(rootCursor, keyPair);
```

## Design

See [docs/SOCIAL_DESIGN.md](docs/SOCIAL_DESIGN.md) for the full lattice architecture.

## License

Copyright 2017-2025 The Convex Foundation and Contributors

Code in convex-social is provided under the [Convex Public License](../LICENSE.md).
