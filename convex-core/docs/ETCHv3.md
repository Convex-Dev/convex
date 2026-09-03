# Etch v3: Header, Durability and Encryption

Etch v3 is the current on-disk format for Etch, the append-only content-addressed
store behind every Convex peer and lattice node. It keeps the v2 data-record and
index-block layout specified in [CAD047](https://docs.convex.world/docs/cad/etch)
and changes only what sits around it: a duplicated, self-checking header that
makes a completed `sync` a real durability boundary, an explicit clean-checkpoint
state so a healthy file reopens without scanning, and an optional length-preserving
encryption overlay below the CAD3 layer. This document is the design reference for
those three additions and the maintenance model that goes with them (unsafe
inspection, repair, migration). CAD047 remains the specification of the body layout
and CAD049 of garbage collection; neither is repeated here.

## Key points

- **The body is unchanged from v2.** Cell encodings, hashes, record layout, index
  blocks and reference-status semantics are identical. Encryption is a storage
  overlay that transforms bytes in place; it never alters what a record means.
- **Fixed layout, never relocated.** Two 4 KiB header copies at `0x0000` and
  `0x1000`, then the fixed root index at `0x2000`. The root index has the same
  position for the life of the file, including through recovery.
- **Ordinary writes never touch the header.** A write appends a record or child
  index and publishes it with one aligned eight-byte slot write. No force, no
  header update, no per-record framing.
- **`sync` is the only full durability boundary.** It forces the body, then commits
  a new header generation naming `syncedFileEnd` and the root. Bytes below
  `syncedFileEnd` in the selected generation are the trusted synced portion.
- **Headers are self-checking.** Each copy carries SHA-256 (plaintext) or
  HMAC-SHA-256 (encrypted) over its used prefix. Open validates both copies and
  selects the highest valid generation, so torn writes and wrong keys are detected
  before any index or data is interpreted.
- **`OPEN` means "do not trust the index".** A header committed as `CLEAN_CLOSED`
  reopens fast; one left `OPEN` by a crash makes normal open refuse. Recovery is an
  explicit decision (restore a backup or `etch repair` into a fresh file), never an
  implicit scan on the hot path.
- **Repair is read-only on the source.** It rebuilds a new file from independently
  valid hash-and-encoding pairs and proves the selected synced root; salvaged tail
  data never supplies a newer root.
- **Encryption uses a caller-supplied 32-byte master key.** HKDF-SHA-256 with the
  per-file salt derives one file cipher key and one header-MAC key. AES-256-CTR or
  ChaCha20 act as random-access XOR keystreams addressed by absolute file offset.
- **The index is plaintext by default.** Optional index encryption is single-snapshot
  obfuscation only: a fixed-offset XOR overlay cannot hide the history of a mutable
  slot from an observer holding several versions of the file.
- **v1 and v2 files migrate; they are not upgraded in place.** New files default to
  v2 unless a v3 configuration is requested.

## Priorities and trust model

V3 ranks its goals: read and write performance first (ordinary writes force nothing
and rewrite no header), lock-free eight-byte slot publication second, then a clear
durability boundary at `sync`, detection of torn headers, wrong keys and invalid
post-crash pointers, lossless migration from v1 and v2, and finally a minimal fixed
header (extensibility is a possible v4 concern).

Recovering writes made after the last completed `sync` is deliberately **not** a
requirement, and normal access never re-decodes or content-hashes the synced portion.
Media corruption, deliberate modification and storage that lies about a completed
force are handled by explicit validation (`etch validate`), repair or backup
restoration, not by mandatory verification on the read path.

## File layout

```text
0x0000  4096-byte header copy A
0x1000  4096-byte header copy B
0x2000  fixed root index (65,536 eight-byte slots, 524,288 bytes)
        appended data records and child index blocks
```

The two copies sit on separate 4 KiB pages so that one torn page or damaged
filesystem block cannot take out both; a header commit writes only the inactive
page. Duplicated headers protect header metadata only. They do not implement index
transactions: index crash behaviour comes from ordered, atomic slot publication.

All header integers are big-endian, as in v1 and v2. Offsets are unsigned 64-bit
file positions; every index slot and child-index start is eight-byte aligned.
Reserved bytes are written as zero and a header containing a non-zero reserved byte
is rejected. Assigning them meaning requires a new format version.

### Header fields

The first four bytes are the complete format probe: `u16 magic = 0xe7c6`,
`u16 version = 3`. Offsets are relative to the start of each copy.

| Offset | Size | Field | Meaning |
|---:|---:|---|---|
| `0x000` | 2 | `magic` | `0xe7c6` |
| `0x002` | 2 | `fileVersion` | `3` |
| `0x004` | 2 | `cipherId` | File overlay cipher; `0` (`NONE`) means plaintext data |
| `0x006` | 2 | `indexEncryption` | `0` plaintext index, `1` index uses the file cipher |
| `0x008` | 8 | `generation` | Monotonically increasing header-commit generation |
| `0x010` | 8 | `syncedFileEnd` | Exclusive end of file contents covered by this sync |
| `0x018` | 8 | `indexStartOffset` | Always `0x2000`; retained as a sanity check and repair input |
| `0x020` | 32 | `rootHash` | Current root hash; all zero means never assigned |
| `0x040` | 32 | `fileSalt` | Immutable CSPRNG output for per-file key separation |
| `0x060` | 32 | `publicKeyHint` | Expected application public key; all zero means unset |
| `0x080` | 8 | `closeState` | `0` `OPEN`, `1` `CLEAN_CLOSED` |
| `0x088` | 3928 | `reserved` | Written as zero |
| `0xfe0` | 32 | `headerCheck` | HMAC-SHA-256 when encrypted, SHA-256 when plaintext |

`cipherId`, `indexEncryption`, `indexStartOffset`, `fileSalt` and `publicKeyHint`
are immutable and must agree between valid copies. Rekeying, changing cipher or
changing the hint means migrating into a new file.

`publicKeyHint` is application metadata, not a key-derivation instruction: an
application may use it to locate the secret it will hand to Etch, but Etch accepts
only the resulting opaque master key. Because the header is plaintext the hint can be
read before key lookup, and it is untrusted until the selected header's check
verifies. A non-zero hint makes copies of the store linkable to that key, so
privacy-sensitive applications should leave it zero.

### `syncedFileEnd`

`syncedFileEnd` is the exclusive end of the logical extent covered by the header
generation: root index, data records, child indexes and alignment padding. It is
at least `indexStartOffset + 524288`. It is not the physical file length, which may
be longer because of mapping granularity, preallocation or unsynchronised appends.

The writer keeps a separate in-memory append cursor. Appends advance the cursor
immediately; only a successful `sync` copies a snapshot of it into a new header
generation. On a clean reopen the cursor is initialised from `syncedFileEnd`; bytes
beyond it are not automatically part of the store.

Comparing `syncedFileEnd` with the physical size is useful but is not a dirty flag:
a shorter physical file proves truncation; a longer one proves only that a tail
exists; equality proves nothing, because an in-place slot or status update does not
extend the file. The field also provides no rollback: it cannot restore an index slot
overwritten after the last sync.

### Clean-checkpoint state

`closeState` is covered by `headerCheck`, so it is committed as part of a complete
generation and never flipped as an unchecked flag. A selected `CLEAN_CLOSED` header
means the previous writer completed a body force, committed this header and made no
later mutation. The name records a clean *checkpoint*: the writer need not have
released its handle.

The state must be invalidated durably before mutation:

1. A read-only open leaves `CLEAN_CLOSED` untouched.
2. Before the first mutation after a clean checkpoint, the writer commits and
   forces the inactive copy with `generation + 1`, the same root and
   `syncedFileEnd`, and `closeState = OPEN`. Mutation proceeds only after that force.
3. Further writes keep `OPEN` and add no marker force.
4. `sync` forces the body, then commits and forces a header with `CLEAN_CLOSED`.
5. `close` performs the same commit only when the file is dirty. Once close begins,
   resource cleanup continues even if a force or header commit fails; such a close
   is dirty by definition and is logged as a warning.

Each sync interval therefore costs one extra forced header transition, never one per
record, and every completed durability boundary is directly reopenable. A crash
during either transition selects a complete old or new generation; an `OPEN` result
is conservative and requires backup restoration or explicit repair.

### Header verification and selection

`headerCheck` covers exactly the 136-byte used prefix (`0x000` to `0x087`), not the
zero padding. A copy is valid only when the prefix satisfies the v3 invariants, every
reserved byte is zero, and the check matches. A mixture of old prefix and new check
(or the reverse) is rejected, so a torn write across the copy is detected. For
encrypted files the keyed check also verifies the supplied key and authenticates the
immutable fields; for plaintext files the unkeyed hash detects tearing and accidental
corruption only and offers no protection against an attacker.

On open the implementation checks both copies, validates each check, and selects the
valid copy with the highest unsigned `generation`. If the apparently newer copy is
invalid, the older valid copy is used and degraded redundancy is reported. If neither
copy validates the open fails with a wrong-key or damaged-header error. Two valid
copies with the same generation must be byte-identical; otherwise the state is
ambiguous. A generation never wraps: a file at the maximum requires migration before
another commit. Preventing rollback by a malicious party needs trusted state outside
the file and is not a v3 guarantee.

On creation the root index is initialised and forced before either header is valid;
both copies are then written and forced in `OPEN` state, in generation order, so the
new file has two recoverable copies and one unambiguous newest.

## Durability contract

### Normal writes and index publication

The default write path is: ensure the selected header is already durably `OPEN`;
append the complete data record or child index; publish the pointer to it with one
aligned eight-byte slot write using release semantics; continue without forcing.
Readers acquire the whole slot before following it. With index encryption enabled,
the writer encrypts the logical slot to one eight-byte ciphertext and publishes that
atomically; encryption never turns one slot update into several physical writes.
This gives process-level publication ordering. Neither Java memory semantics nor an
aligned mapped store is a portable promise about power-loss ordering.

Index algorithms preserve a valid old view until their single publication write:

- a new entry is appended completely before its empty slot is filled;
- converting a plain slot into a chain appends the new record first, changes the
  original slot from `PTR_PLAIN` to `PTR_START`, then publishes the new pointer as
  `PTR_CHAIN`, so a failed publication leaves only an unreachable record;
- replacing a chain with a child index builds the whole child first and repoints
  the parent slot to `PTR_INDEX` last; old continuation slots are cleanup, cleared
  afterwards, and a lock-free reader that started from the old `PTR_START` re-reads
  that slot before returning a miss and retries the level if it changed.

These rules keep live readers safe and improve crash recovery without adding a force
to the hot path.

### `sync`

`sync` (the v3 implementation of the existing `flush` API) is the sole full
durability boundary:

1. briefly exclude writers and snapshot the pending root and append end;
2. force all data and index mappings, then the file contents and required metadata;
3. write the inactive header copy with `generation + 1`, the new root and
   `syncedFileEnd`, `closeState = CLEAN_CLOSED` and its `headerCheck`;
4. force only that already-allocated 4096-byte copy;
5. return and release writers.

The body is forced before the new header can become valid, so a completely written
new header that reaches storage early is still safe to select. A failed body force,
header write or header force is reported to the caller and the in-memory active
generation advances only after the final force succeeds. After later unsynchronised
writes begin, a crash may preserve any mixture of them; exact rollback to the last
generation is not guaranteed, because in-place index pages can be written back by the
operating system before `sync`.

Normal access trusts synced bytes without recomputing content hashes, but still
bounds every access and fails on structural errors it meets. Full content
verification belongs to `etch validate`, migration verification and repair.

### Recovery by crash point

Opening validates both header copies (SHA-256, or HMAC-SHA-256 under a key derived
from the resolved master key), selects the highest valid generation and requires the
physical file to reach at least the selected `syncedFileEnd`.

| Crash point | Result |
|---|---|
| After a successful `sync` or `close`, before another mutation | `CLEAN_CLOSED` fast path: header check plus minimum-length check; no index or data validation; a mutation first performs the forced transition to `OPEN` |
| During the first-write transition to `OPEN` | Normal open fails; backup restoration or explicit repair required, even though no later mutation may have begun |
| While writing the new header | Select the new header if its check validates, otherwise the older valid header |
| During the body force, or after later unsynchronised writes | Normal open fails; repair reconstructs a fresh file and validates the selected root; a root that fails to verify requires backup restoration |

`OPEN` means only that mutation began after the last clean checkpoint; it does not
prove that anything was lost. There is deliberately no writable recovery-open mode:
an uncertain index must never become the base for further in-place mutation.

## Maintenance: unsafe open and repair

### Unsafe maintenance open

Migration and repair need to inspect a file normal open has rejected. "Unsafe"
describes the consistency assumptions a caller may make about the source, not weaker
bounds checking or permission to modify it. A maintenance open:

- exposes only a read-only API and mapping;
- holds a lock that excludes writers for the life of the operation;
- validates and selects a header, including key verification, but bypasses the
  `OPEN` rejection and accepts that the index may be inconsistent;
- performs no clean-state transition, header commit, sync, truncation, tail adoption
  or other source mutation;
- bounds every read independently and treats every pointer, length, status and
  metadata field as untrusted;
- defaults index-based reads to the selected `syncedFileEnd`, while repair scans
  through a snapshot of physical EOF.

`EtchMaintenanceReader.openUnsafe` takes a shared writer-excluding lock for
inspection; `openExclusive` takes the exclusive lock reconstruction needs. Both
snapshot physical EOF and expose bounded metadata, raw, cipher-overlay data and
index reads. No `Etch` or `EtchStore` mutation API is reachable from either. For v1
and v2 files the same facility uses their recorded logical length as the default
bound.

### Explicit repair

Repair is distinct from garbage collection: GC starts from a normally readable index
and discards what the root does not reach; repair assumes the index may be unusable,
rediscovers cells from immutable records and proves the selected synced root
complete. It is offline, read-only on the source and always directed into a fresh
file. Under the exclusive lock it:

1. validates and selects a header (root, `syncedFileEnd`, cipher parameters) and
   snapshots physical EOF;
2. walks whatever index blocks remain structurally readable, copying independently
   valid records and reporting bad slots or subtrees;
3. scans the whole body sequentially through physical EOF, decrypting at absolute
   offsets where necessary, accepting a candidate only after bounds, canonical CAD3
   and content-hash validation and ignoring mutable labels;
4. writes every accepted hash-and-encoding pair into the new file, building a new
   index without trusting the source index, and rejects conflicting encodings for
   one hash;
5. sets the destination root only when every value reachable from the selected
   synced root is present and valid, then syncs, cleanly closes, reopens and fully
   validates the destination.

A candidate crossing physical EOF is incomplete and rejected. A valid cell beyond
`syncedFileEnd` is copied and reported as salvaged tail data, but it cannot supply a
newer root because no such root was ever committed in a header. Success needs both
a fully persisted selected root and a scan that reached the captured EOF: a complete
root with an interrupted scan reports `ROOT_RECOVERED`; an incomplete root reports
`PARTIAL` and leaves the destination root unset. Both are valid partial stores, never
a replacement for the source, which repair never modifies. Copying all valid records
in one sequential pass avoids holding a complete hash map for a terabyte-scale
source; an ordinary GC can compact the result later.

`etch recover` is a different operation: it completes or rolls back an interrupted
GC cycle (see `ETCH_GC.md`), and does not read a damaged index.

## Body layout (retained from v2)

Keeping the v2 body avoids a new decoding branch on ordinary reads and makes
migration a logical copy rather than a CAD3 conversion. When encryption is enabled the
following bytes are transformed in place without changing offsets or lengths.

A data record is unaligned:

| Size | Field |
|---:|---|
| 32 | CAD3 content hash (the Etch key) |
| 1 | monotonic reference flags and status |
| 8 | cached memory size, big-endian; zero means not recorded |
| 2 | positive signed encoding length, big-endian |
| N | canonical CAD3 encoding |

Valid encodings are bounded by `Format.LIMIT_ENCODING_LENGTH`. The nine-byte label
(flags and memory size) is the only mutable part of a published record.

An index block is an aligned array of big-endian eight-byte slots: 65,536 in the
fixed root, 256 at level one, 16 at every deeper level. The two high bits select
`PTR_PLAIN`, `PTR_INDEX`, `PTR_START` or `PTR_CHAIN`; the remaining 62 bits are the
absolute file offset. No checksum, marker, epoch or second physical slot is added:
journalling, dual slots or copy-on-write pages would strengthen unsynchronised-crash
recovery but change the format and belong to a future version, and forcing before
every slot is too slow to be the default.

## Encryption overlay

The header selects one file cipher and independently chooses whether the index uses
it. Data is encrypted whenever `cipherId` is not `NONE`; the header itself is always
plaintext so an implementation can discover the format, obtain key material and
explain failures.

| ID | Name | Use |
|---:|---|---|
| `0` | `NONE` | Identity overlay |
| `1` | `AES_256_CTR` | Length-preserving AES counter-mode keystream |
| `2` | `CHACHA20` | Length-preserving ChaCha20 keystream |

Any other cipher identifier, or an `indexEncryption` value other than `0` or `1`, is
rejected; `indexEncryption` must be `0` when the cipher is `NONE`. Both encrypted
forms are random-access XOR overlays: they preserve offsets and lengths and can
transform an eight-byte slot without widening it. Authenticated formats such as
AES-GCM need nonces and tags, hence extra framing, and belong to a future version.

### Caller key material and verification

An encrypted configuration supplies a synchronous key function
(`EtchConfig.withKeyFunction`). Etch calls it during create or open with the file's
`publicKeyHint`, or `null` when unset; it may block on a keystore, hardware device or
prompt, and an unchecked exception aborts the open. For a new path key resolution
happens before the file is created. Plaintext files never invoke it.

The function returns an exactly 32-byte master key that stays owned by the caller.
Etch reads it synchronously to derive the file keys and drops the reference: it never
retains, modifies or wipes the caller's array. Etch owns the derived keys and cipher
state and wipes them when an open fails or the owning `Etch` or
`EtchMaintenanceReader` closes.

The hint is a lookup aid, not authenticated input at call time: the master key is
needed to authenticate the header that contains it. Applications must not treat an
unverified hint as authority for anything beyond selecting a candidate key. The keyed
`headerCheck` is also the key verifier: it is compared in constant time and rejects
a wrong key before any index or data is interpreted. Like every offline verifier it
permits offline guessing if the master key is derived from low-entropy input, so
passphrases must first pass through a password-hardening KDF.

Where the master key comes from is outside the file format. Convex offers one
optional interoperable derivation for applications that hold a 32-byte high-entropy
source such as an Ed25519 seed and do not want to use it directly:

```text
masterKey = HKDF-SHA-256(salt = none, IKM = sourceKey,
                         info = "convex-etch-master-key-v1", L = 32)
```

This is `EtchKeyDerivation.deriveMasterKey`. The Convex CLI applies it to the
selected peer or keystore key when opening encrypted stores; the CLI-side resolution
rules (which key is chosen, the `--etch-key*` and `--into-key*` options) are
documented in `convex-cli/README.md`.

### File key derivation

V3 derives exactly two file-scoped keys from the master key with RFC 5869
HKDF-SHA-256, using `fileSalt` as the salt and fixed ASCII `info` labels:

```text
PRK = HMAC-SHA-256(key = fileSalt, data = masterKey)
key = HMAC-SHA-256(key = PRK, data = ASCII(info) || 0x01)

info = "convex-etch-v3-file-cipher"   file cipher key, used at every encrypted offset
info = "convex-etch-v3-header-mac"    header-MAC key, used only for headerCheck
```

Both keys are 32 bytes, so only the first expand block is used; `0x01` is HKDF's
block counter, not part of the label. Separate keys avoid using one key with both a
stream cipher and HMAC.

`fileSalt` is generated once from a CSPRNG when the file is created, stored in
plaintext in both copies and never changed by `sync`. An exact backup may keep the
salt because it keeps the same file identity, but two copies with the same salt and
key must never be modified independently: divergent writes at one offset would reuse
keystream. An independently writable copy therefore requires migration with a fresh
salt.

### Keystream addressing

Cipher addressing starts from the absolute byte offset in the file and never resets at
an index or record. Data and index occupy disjoint offsets, so they share one
keystream namespace safely. For offset `p` and cipher block size `B`:

```text
blockNumber = floor(p / B)
byteInBlock = p mod B
locator     = I2OSP(blockNumber, 16)     // unsigned big-endian, 128 bits
```

AES-256-CTR has `B = 16` and uses the locator directly as the initial counter block,
incrementing the full 128-bit value per block. ChaCha20 has `B = 64` and splits the
same locator into a 96-bit nonce prefix and a 32-bit big-endian counter suffix; each
nonce addresses `2^32` blocks (256 GiB), after which the nonce prefix increments and
the counter restarts at zero. A request crossing that boundary is split. The Java
implementation drives Bouncy Castle's ChaCha core with Etch's own locator and carry
logic rather than inheriting a provider's IV conventions. Canonical locator and
keystream test vectors live in `EtchCipherLocatorTest`, `AES256CTREtchCipherTest`,
`ChaCha20EtchCipherTest` and `EtchKeyDerivationTest`.

Because slots are eight-byte aligned, a slot never crosses an AES or ChaCha block
boundary, so its ciphertext can be computed from its own offset and published
atomically.

### Index encryption limitation

A plaintext index exposes offsets and shape but keeps inspection and recovery simple
and avoids cipher work on the hottest read path; data may still be encrypted.
Encrypting the index gives single-snapshot obfuscation only: a slot is mutable at a
fixed offset and so reuses its keystream, two snapshots reveal the XOR of two
pointers, and a snapshot of a known-zero slot reveals the keystream itself. This is
inherent to fixed-offset XOR overlays, not to either cipher; strong multi-snapshot
confidentiality needs a wider slot with nonce and tag or versioned index pages, which
would change the atomic eight-byte update model and are deferred. The implementation
reports the confidentiality level of an encrypted index rather than implying
authenticated encryption.

The mutable nine-byte data label has the same property. It stays inside the
contiguous encrypted record so one sequential cipher operation covers the whole
record; across snapshots it leaks only the XOR of old and new labels, never keystream
for the adjacent immutable bytes. XOR overlays are also malleable: CAD3 hashes let
decrypted values be verified against their keys and pointer validation catches many
index modifications, but this is not whole-file authentication, which would require a
future version.

## Migration from v1 and v2

V1 and v2 place their root index at byte 44 or 64, leaving no room for the v3 header
copies, so v3 is adopted by lossless migration into a fresh file. A conforming
migration opens the source read-only and quiescent, creates the destination with its
chosen cipher and index-encryption setting, visits every source index entry
(including values not reachable from the root), preserves each value, hash, status
and memory-size metadata, preserves the root exactly (including the distinction
between an unassigned root and an explicitly stored null root), syncs and validates
the destination, compares entry inventories, and leaves the source untouched until an
explicit cutover. Physical offsets and index shape are not data and need not be
preserved. A crash during migration leaves the source valid and the destination
disposable.

Strict migration uses normal fail-fast open. The designed complement is a lenient
migration for a source that normal open rejects: an unsafe maintenance open drives a
lenient index walk that copies every independently readable indexed cell, records the
slots and subtrees it could not traverse, and reports two independent results:
**index coverage** (complete only if the whole walk finished with nothing skipped)
and **root persistence** (complete only if the selected source root verifies
transitively in the destination). Setting the destination root is permitted only on
complete root persistence. Full-file repair remains the index-independent path.

## Not yet implemented

- Lenient (`--unsafe`) migration as a CLI command. The unsafe reader and the
  coverage/root-persistence contract above are designed; the CLI currently offers
  strict `etch migrate --into` and index-independent `etch repair --into`.
- Failure-injection coverage around body force, header publication and dirty close.

## Where the code lives

All classes are in `convex.etch` in `convex-core` unless stated.

- `Etch`: file access, open selection, `OPEN` rejection, sync and close.
  `EtchStore`: the `AStore` binding. `EtchConfig`: creation policy (`createV3`,
  `CipherMode`, `withKeyFunction`, `withPublicKeyHint`).
- `AEtchHeader`, `EtchV3Header`, `LegacyEtchHeader`: header parsing, verification and
  commit. `EtchConstants`: the `V3_*` offsets, sizes and identifiers.
- `EtchFileCipher` with `AES256CTREtchCipher` and `ChaCha20EtchCipher`;
  `EtchCipherLocator` for offset-to-locator mapping; `EtchKeyDerivation` for the
  master-key and file-key HKDF derivations.
- `EtchMaintenanceReader` (unsafe open), `EtchRebuilder` (repair, with
  `Status.COMPLETE`, `ROOT_RECOVERED`, `PARTIAL`), `EtchStrictValidator` (offline
  validation), `EtchUtils` (migration and GC recovery).
- CLI (`convex-cli`, `convex.cli.etch`): `etch info`, `etch validate`,
  `etch migrate --into [--set-root]`, `etch repair --into`, `etch gc`,
  `etch recover`. Key and destination-policy options come from
  `convex.cli.mixins.EtchConfigMixin`.
- Tests: `EtchV3HeaderTest`, `EtchV3IntegrationTest`, `EtchVersionMatrixTest`,
  `EtchMaintenanceReaderTest`, `EtchRebuilderTest`, `EtchStrictValidatorTest` and the
  cipher tests named above.

## Related

- [CAD047: Etch Storage Format](https://docs.convex.world/docs/cad/etch): body
  layout, index blocks, records and the v1 header.
- [CAD048: Lattice Stores](https://docs.convex.world/docs/cad/stores): the store
  abstraction and persistence status above Etch.
- [CAD049: Etch Garbage Collection](https://docs.convex.world/docs/cad/etch_gc) and
  [ETCH_GC.md](ETCH_GC.md): online collection, migration and GC recovery.
- [CAD003: Encoding Format](https://docs.convex.world/docs/cad/encoding): the CAD3
  encodings and content hashes Etch stores.
- [CAD052: Encryption](https://docs.convex.world/docs/cad/encryption): message-level
  encryption envelopes, distinct from this storage overlay.
- `convex-cli/README.md`: operator-facing key resolution for encrypted stores.
