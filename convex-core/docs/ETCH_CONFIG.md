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
| `refCacheSize` | `10000` | Positive integer number of L1 reference-cache slots, at most `Integer.MAX_VALUE`. Allocated separately for each store. |
| `enableL2` | `true` | Enable the L2 soft-reference cache for decoded cells. Entries may be reclaimed under JVM memory pressure. |

L1 is an array cache; increasing its capacity reduces collision evictions at the
cost of more heap per store. L2 has no entry-count limit. Disabling it leaves L1
active. These defaults preserve the behaviour of existing callers.

Each L1 cache belongs to one store and checks references on insertion. Stored
entries use `RefSoft` bound to that store, including embedded top-level values.
Newly decoded values can reuse their existing direct ref at `UNKNOWN` status,
avoiding a soft-reference allocation before persistence. Such direct entries
strongly retain their values until eviction; the slot count bounds the number
of entries, not their total memory size. Null uses its store-independent ref.

L2 caches decoded values. A shared cell's attached ref can subsequently change
when it is persisted elsewhere, so promotion to L1 checks that ref and discards
foreign status claims. It reuses the decoded cell without changing its attached
ref. Ownership enforcement adds no checks or allocations to L1 hits.

## Java usage

```java
EtchConfig config = EtchConfig.parse("""
    { refCacheSize: 50000, enableL2: false }
    """);
try (EtchStore store = EtchStore.create(new File("data.etch"), config)) {
    EtchConfig effective = store.getConfig();
    AMap<AString,ACell> resolved = effective.getMap();
}
```

`EtchStore.createTemp(config)` uses the same settings. Existing factory methods
and the `EtchStore(Etch, boolean)` constructor remain available; the boolean
overrides L2 for that store and is included in `store.getConfig()`.

`getMap()` returns an immutable map containing all resolved settings, including
defaults and the concrete mapping backend selected for `"auto"`. It can be
serialised as JSON and passed back to `fromMap` or `parse`. The key resolver and
secret key material are never included. For encrypted configuration, supply the
resolver separately to `fromMap(map, resolver)` or `parse(json5, resolver)`.
Use `mapping: "auto"` again when selecting a backend for a different runtime.

Configuration getters read final Java fields. Map lookups, validation and default
resolution happen only during configuration construction. Store construction
allocates L1 and optionally L2 once; decoding, hash lookup and persistence access
those caches directly. Index writes use Etch's cached `buildChains` boolean.
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

GC inherits the complete resolved configuration. Its successor starts with fresh
caches using the same capacity and L2 setting; references bound to the predecessor
are not copied into the successor's caches. The compatibility constructor's L2
override also survives cutover. See [Etch GC](ETCH_GC.md) for ownership and
snapshot lifetime, and [Etch v3](ETCHv3.md) for encryption.

## Peer configuration

The same map is accepted as `peer.etch` in the peer's JSON5 configuration file:

```json5
{
  peer: {
    store: "data/peer.etch",
    etch: {
      refCacheSize: 50000,
      enableL2: false
    }
  }
}
```

The file location remains `peer.store` (or the Java factory's `File` argument).
Runtime cache settings are applied once when the peer opens the store.
