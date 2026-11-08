# GC cutover and snapshot crash contract

This proposal makes the JVM recovery policy and its test boundary explicit for
issue #748. It follows [CAD049](https://docs.convex.world/docs/cad/etch_gc) and
[ETCHv3](../ETCHv3.md). It does not claim portable power-loss durability or completion
of the v3 release gate merely because unit tests pass.

## Guarantees and commit point

The supported guarantee is process-crash recovery on a filesystem implementing
atomic same-directory replacement and hard links. The target must be complete and
checkpointed before any cutover metadata is published. In order:

1. Verify the target root and force a CLEAN_CLOSED target checkpoint.
2. If requested, checkpoint the source and create a new backup hard link.
3. Publish a forced temporary tombstone by atomic replacement.
4. Publish a forced temporary completion marker by atomic replacement. This is
   cutover's filesystem commit point.
5. Publish the successor handle. The caller controls the original handle's lifetime.
6. Retire original files only when their dependent handles/leases permit it.
7. On restart, authenticate participating stores, retire superseded names, adopt
   the selected target under the base name and remove the marker.

File force does not prove durability of link, rename or unlink directory entries.
The implementation does not force directories. Thus a machine-power-loss claim
requires a separate platform/filesystem qualification and ordering mechanism;
neither the Linux test run nor simulated Windows denial establishes it. Unsupported
atomic replacement fails; it does not fall back to truncating a marker.

## Markers and ambiguous acknowledgements

New markers use exactly four lines: the existing target filename, the version tag
`etch-gc-v1`, an identity, and a publication attempt. V3 identities are
`v3-salt <hex>` binding that name to the target's authenticated immutable file salt.
Legacy-format files use `legacy` as their identity. A final `attempt <uuid>` line distinguishes this publication from stale metadata.
These are recovery metadata, not secrets or substitutes for header authentication.
The salt is checked against a validated v3 header before layout changes. It stays
constant through later writes; dirty headers are still rejected before mutation.
It identifies a logical file, not a particular checkpoint generation. Externally
restoring an older copy with the same salt, maliciously replacing metadata, or
mixing files from independent backups is outside this managed-layout contract.
This is not rollback protection: that requires trusted state outside these files.

If atomic publication succeeds but reports an IOException, or temporary cleanup
fails after publication, an exact match of the marker to this unique attempt
proves cutover committed. The operation completes in memory and preserves the
tombstone and requested snapshot. If publication definitely did not occur,
cleanup is attempted and its failures are suppressed on the original exception.
Inspection reads the marker directly; only `NoSuchFileException` proves absence.
A failed file-type/stat probe must not be treated as an absent marker.
If the marker cannot be inspected, the error and evidence are retained; callers
must close the handles and resolve recovery before continuing lifecycle changes.

A malformed marker is refused, even if a base file exists. A marker naming a
missing target is accepted as already adopted only when its v3 identity matches
the base. A reused target filename with a different identity is refused. Recovery
never infers successful adoption merely from the existence of a base filename.
A rename that succeeds but reports failure is reconciled by the same identity;
otherwise it fails without creating an empty replacement.

The parser accepts historical exactly-one-line markers or this complete versioned
record. Unknown, truncated or extra lines are refused instead of dropping identity
checks. Historical documentation allowed informational extra lines, although the
writer emitted only one line: such manually extended markers now need inspection.
Do not automatically strip lines from a damaged record to make recovery proceed.

Compatibility: older readers understand the first line and ignore extra lines,
but do **not** gain these identity checks. Existing one-line markers can still
recover when their named target exists and the layout authenticates. An old marker
whose target has disappeared cannot prove adoption and now requires operator
inspection; preserve the files rather than deleting the marker blindly. Legacy
v1/v2 markers have no stable v3 salt and share that limitation. This is a deliberate
fail-closed recovery change, not an on-disk Etch body-format change. Do not downgrade
a recovery operation expecting the stronger policy.

## Snapshots

A snapshot is confirmed by successful `completeGC(backup)` return, followed by
closing the caller's original/older dependent handles before opening the backup.
Success does not authorise the library to close those handles itself. Live writes
then route to the successor and cannot change the retained old inode.

A backup name left by a failed or interrupted operation is **unconfirmed**. Before
commit it may still alias the source that rollback subsequently mutates. Its mere
existence, or a valid Etch header, is not proof of the requested snapshot root.
No standalone snapshot certificate is introduced here. After losing the API
acknowledgement, inspect the cutover record and independently validate the backup
against the application's recorded original root before treating it as confirmed.
Otherwise retain it as evidence or create a new independent checkpoint.

Cleanup may remove a newly created unconfirmed link after a failed attempt, but
must never erase a link belonging to a proven committed attempt. A cleanup failure
can leave an occupied unconfirmed path; retry uses a new path or explicit operator
cleanup. Proposed paths are rejected if occupied, in the live GC namespace, or
associated with stale completion/defunct/target sidecars. Confirmed backups are
outside recovery's retirement and rollback set and remain unchanged by subsequent
live writes, recovery and collections.

## Fault matrix

`EtchGCFileFaultTest` injects failures before and after actual lifecycle filesystem
operations through a package-private operation boundary. It records real file
images and hard-link aliases and restarts from copies. It composes with the separate
v3 byte/header matrix; it does not duplicate header-prefix testing.

| Boundary or layout | Required outcome |
| --- | --- |
| Target checkpoint before marker | Original checkpoint or complete rolled-back target; never empty |
| Snapshot link, metadata temp/force/replacement/cleanup | Failed uncommitted attempt, or proven committed successor |
| Successful replacement with lost acknowledgement | Retain committed tombstone and snapshot; return successor |
| Failed cleanup | Preserve error context and evidence; do not claim an unconfirmed snapshot |
| Retirement/unlink/adoption interrupted before or after operation | Committed root remains recoverable; repeated restart is idempotent |
| Deletion denied by pinned mappings | Return selected target and defer adoption; retry later |
| Missing/malformed marker, reused target name, dirty v3 participant, wrong key | Refuse before layout mutation |
| Cancel/retry and repeated cycles | Retained root complete; confirmed snapshot unchanged |
| Backup with stale sidecars | Reject proposed backup path without deleting sidecars |

Tests run plaintext, AES-CTR and ChaCha20 with both supported index modes. The
portable workflow runs the GC tests on Linux and Windows with JDK 21 and 25.
A simulated deletion denial covers the deferred-adoption branch on Linux; it is
**not an actual Windows result**. Windows MBB/FFM acceptance remains pending until
those jobs run on real Windows. No storage-hardware power-cut test is included.

Run locally (PowerShell uses `.\mvnw.cmd`):

```sh
./mvnw -B -pl convex-core -am -Dtest=EtchGCFileFaultTest,EtchGCRecoveryTest,EtchGCLifecycleTest,EtchConfiguredLifecycleTest -Dsurefire.failIfNoSpecifiedTests=false test
```
