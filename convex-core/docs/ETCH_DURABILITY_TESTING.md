# Testing Etch v3 crash boundaries

The contract under test is [ETCHv3](ETCHv3.md), with the body format described by
[CAD047](https://docs.convex.world/docs/cad/etch). `EtchDurabilityMatrixTest`
executes the production mutation, index, checkpoint, close and repair code through
a test-only mapper. No extra write, check or force is added to ordinary storage
operations. Its package constructor complements the existing deterministic-mapper
constructor and is not a public embedding API.

`DurableEtchFileMapper` owns separate live and durable images. Writes update only
live bytes. Full and range forces copy only their specified ranges into the crash
image. An injected failure can persist none, half or all of the affected range
before reporting failure; this includes an operation whose acknowledgement is lost.
Reopening always uses a copy of the durable image, not the live buffers. A cut
freezes subsequent storage operations, so Java cleanup cannot accidentally turn a
failed checkpoint into a successful one.

The default suite runs all five supported combinations: plaintext; AES-CTR with
plain/encrypted indexes; and ChaCha20 with plain/encrypted indexes. Real colliding
content hashes trigger child-index construction with chaining disabled. Each run
changes the root and upgrades a recorded reference status. The matrix cuts each
record segment, child-index write, aligned slot publication, OPEN header write and
force, body force, CLEAN_CLOSED header write and force. It exercises both explicit
sync and final close. Cleanup failure checks include suppressed exceptions and
cryptographic-resource destruction.

Each operation cut is selected from an uninterrupted execution trace and rerun
from an identical checkpoint. After a cut, an authenticated clean image must
open with its selected root complete; an OPEN image must be rejected by normal
open. Both then go through actual read-only-source reconstruction, with byte-for-byte
source preservation and destination-root completeness checks. Additional cases
salvage valid tail cells without promoting an uncommitted root, and refuse to
publish an incomplete root.

Header selection additionally enumerates every prefix through the used header
fields and every prefix through the authentication check. The intervening reserved
zero padding creates no further distinct states. It tests an entirely unforced
live header separately. A **complete valid** header that reaches storage early may
be selected: the body force precedes it. This is explicitly permitted by ETCHv3;
there is no persisted bit recording whether force returned. A partial invalid
header cannot win, and a reported force failure never advances the writer's
in-memory generation. This distinction resolves the informal “unforced header”
wording in issue #684.

Run from the repository root:

```sh
./mvnw -B -pl convex-core -am -Dtest=EtchDurabilityMatrixTest -Dsurefire.failIfNoSpecifiedTests=false test
```

The scoped `Etch durability matrix` workflow runs the same suite on Linux and
Windows with JDK 21 and 25. It uses the default physical backend when reopening
images (MBB on JDK 21; FFM when available on newer JDKs). Every crash image has
fresh filenames. Java 21/Windows may defer deleting closed MBB mappings; fixture
cleanup tolerates that limitation without weakening reopen or repair assertions.
Actual Windows results remain pending until those runners execute.

This deterministic model tests algorithmic ordering, not whether a specific disk,
filesystem or controller honours force under power loss. Directory-entry durability
belongs to the separate [GC filesystem protocol](ETCH_GC.md).
