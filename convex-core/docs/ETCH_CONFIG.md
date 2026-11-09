# Etch configuration

`EtchConfig` defines the file policy and runtime cache settings for an
`EtchStore`. Supply an immutable `AMap<AString,ACell>` to `EtchConfig.fromMap`,
or parse a JSON5 object with `EtchConfig.parse`. Omitted settings use defaults;
unknown keys, incorrect types and invalid combinations are rejected at setup.

## Options and defaults

| Key | Default | Meaning |
|-----|---------|---------|
| `version` | `2` | Format to use for a new file: `1`, `2` or `3`. |
| `mapping` | `"auto"` | `"mapped-byte-buffer"` or `"memory-segment"`. Automatic selection uses memory segments when available for v2/v3, otherwise mapped byte buffers. V1 requires mapped byte buffers. |
| `buildChains` | `true` | Build short index collision chains. Runtime policy. |
| `cipher` | `"none"` | V3 file cipher: `"none"`, `"aes-256-ctr"` or `"chacha20"`. Encryption requires a runtime key resolver. |
| `encryptIndex` | `false` | Encrypt the v3 index as well as data. Requires a file cipher. |
| `publicKeyHint` | `null` | Optional 32-byte public key, written as hex, identifying key material for v3. It is not the encryption key. |
| `refCacheSize` | `10000` | Positive integer number of reference-cache slots, at most `Integer.MAX_VALUE`. Allocated separately for each store. |
| `enableL2` | none | Accepted as a boolean so existing configurations load; it has no effect. |

The reference cache is an array indexed by hash; increasing its capacity reduces
collision evictions at the cost of more heap per store. A cell evicted from it is
read from the file or decoded again when next needed.

Each reference cache belongs to one store and checks references on insertion.
Stored entries use `RefSoft` bound to that store, including embedded top-level
values; a cell read from the file enters the cache only that way. Values decoded
with the store's `decode` method can reuse their existing direct ref at `UNKNOWN`
status, avoiding a soft-reference allocation before persistence. Such direct
entries strongly retain their values until eviction; the slot count bounds the
number of entries, not their total memory size. Null uses its store-independent
ref. Ownership enforcement adds no checks or allocations to cache hits.

## Java usage

```java
EtchConfig config = EtchConfig.parse("""
    { refCacheSize: 50000 }
    """);
try (EtchStore store = EtchStore.create(new File("data.etch"), config)) {
    EtchConfig effective = store.getConfig();
    AMap<AString,ACell> resolved = effective.getMap();
}
```

`EtchStore.createTemp(config)` uses the same settings, and the other factory
methods remain available.

`getMap()` returns an immutable map containing all resolved settings, including
defaults and the concrete mapping backend selected for `"auto"`. It can be
serialised as JSON and passed back to `fromMap` or `parse`. The key resolver and
secret key material are never included. For encrypted configuration, supply the
resolver separately to `fromMap(map, resolver)` or `parse(json5, resolver)`.
Use `mapping: "auto"` again when selecting a backend for a different runtime.

Configuration getters read final Java fields. Map lookups, validation and default
resolution happen only during configuration construction. Store construction
allocates the reference cache once; decoding, hash lookup and persistence access
it directly. Index writes use Etch's cached `buildChains` boolean.
There are no configuration-map lookups on these hot paths.

## Opening files and collecting

An existing file's header determines its version, cipher, index encryption and
public-key hint. Supplying different creation settings does not convert that
file. The caller's runtime cache and chain settings still apply, and the mapper
must be compatible with the actual file format. `store.getConfig()` reports the
effective combination.

Cache and chain settings are not persisted in the Etch header. Reopening with
no configuration uses their defaults; supply your configuration on each open.
This also allows two stores in one process to use different cache policies.

GC inherits the complete resolved configuration. Its successor starts with a
fresh reference cache of the same capacity; references bound to the predecessor
are not copied into it. See [Etch GC](ETCH_GC.md) for ownership and snapshot
lifetime, and [Etch v3](ETCHv3.md) for encryption.

## Peer configuration

The same map is accepted as `peer.etch` in the peer's JSON5 configuration file:

```json5
{
  peer: {
    store: "data/peer.etch",
    etch: {
      refCacheSize: 50000
    }
  }
}
```

The file location remains `peer.store` (or the Java factory's `File` argument).
Runtime cache settings are applied once when the peer opens the store.

## Soft references and garbage collection

Cells held through soft references (`RefSoft`) stay in memory until the JVM
reclaims them. By default the JVM keeps a softly referenced value for one second
per megabyte of free heap since its last use. Operators who see heavy
garbage-collection pressure may want to set `-XX:SoftRefLRUPolicyMSPerMB` below
the JVM default of 1000. With the G1 collector, only 0 lets young collections
reclaim them; other values wait for a marking cycle.

These settings reach only cells held softly. A cell that is stored but still held
strongly, by a cursor that has not switched to its persisted value, cannot be
reclaimed however they are set; see
[cursor persistence](LATTICE_CURSOR_DESIGN.md#persistence). The JVM clears soft
references before it runs out of memory, so an out-of-memory failure points to
strong references, not to these settings.
