# CNS — internal design notes

Status: working document, July 2026. Companion to [CAD014](https://docs.convex.world/docs/cad/cns)
(normative specification) and [UPGRADE.md](UPGRADE.md) (network upgrade mechanism). This document
records the implementation design, the verified defects, and the proposed CNS changes for the v1
network upgrade.

## Implementation map

| Component | Location | Notes |
|-----------|----------|-------|
| Standard registry actor | `convex-core/src/main/cvx/convex/core/registry.cvx` | Deployed at `#9` (`*registry*`) during genesis. **Genesis source — consensus-frozen, see below.** |
| Trust library | `convex-core/src/main/cvx/convex/core/trust.cvx` | Deployed at `#10`; the registry locates it as `(address (inc (long *address*)))`. Also genesis source. |
| Genesis CNS tree | `Init.addCNSBaseTree` / `Init.doActorDeploy` / `Init.doCurrencyDeploy` | Creates `convex.*`, `asset.*`, `torus.*`, `currency.*`, the `init` record and the `convex.cns` alias. |
| Java resolution | `Context.lookupCNS` / `lookupCNSRecord`, `State.lookupCNS` | Actor-calls `resolve` / `read` on `#9`. Used by the REST API, GUI, MCP server — and by the v1 Bootstrap migration itself (resolving `convex.fungible`). |
| CVM resolution | `resolve` core macro, `@` reader syntax, `import` | All route through the registry's `resolve`. |
| Code generation | `Code.cnsUpdate` | Emits `(#9/create 'name addr controller)` — used by genesis deploys. |
| Tests | `convex.lib.CNSTest`, `convex.actors.RegistryTest` | `CNSTest.testDelegatedControlTransfer` / `testNodeDeletionOrphans` pin the authority-model semantics, including the open-issue behaviours the v1 upgrade will change. |
| Purchasable-name stubs | `convex/user.cvx`, `app/names.cvx` | Placeholders only — see "User namespaces" below. |

## Data model

The registry holds two top-level structures:

- `cns-database` — map of `path vector` → (`segment name` → `[value controller metadata child]`).
  Each key is a **node**; each entry in a node's map is a **record**. The root node is `[]`.
  A record's `child` field, when non-nil, is a scoped reference to the node holding its children —
  for nodes hosted by the registry itself, `[#9 path]`.
- `cns-owners` — map of `path vector` → **node owner** (a trust monitor). Governs namespace
  structure: creating/deleting entries and transferring node ownership.

Resolution walks the path from the root, one scoped `(call ref (cns-read pname))` per segment, so
any actor implementing the node SPI can host a subtree. `read`/`resolve` are wrapped in `query` so
malicious node code cannot mutate state during resolution.

### Authority model (summary — normative version in CAD014)

Record controllers govern *records* (`:update`); node owners govern *namespace structure*
(`:create`/`:delete`/`:control`). They are separate capabilities **by design**, supporting
delegation: a namespace owner hands out names but retains revocation rights. Full transfer of a
name plus its subtree is two operations: `(*registry*/control 'name new)` for the record and
`(trust/change-control [#9 path] new)` for the node. Subtree nodes are independently owned and may
be shared between records, which is why deletion is deliberately non-recursive.

## Consensus constraints

`registry.cvx` and `trust.cvx` are genesis sources. Editing them changes the genesis hash and the
replay-from-source state hash, breaking identity with live networks (Protonet is at protocol
version 0). Per UPGRADE.md, genesis is never modified: **every change to account `#9` — code,
metadata, even a typo in a string — must ship as a migration** in `Migrations.Bootstrap` (or a
later version). Java-side changes outside the CVM state transition (e.g. `Context.lookupCNS`) and
non-genesis sources (lab code, tests, docs) are unconstrained.

This also sets the deadline structure: adding CNS fixes to the v1 Bootstrap is free while v1 is
unscheduled. Once v1 activates on any network, further fixes require a v2 upgrade.

## Verified defects (July 2026 review)

All confirmed empirically against the upgraded test state:

1. **Record-creation authority is inconsistent with the model.** `cns-write` authorises *new*
   records against the **parent record's controller** (`-controller`), while node/record deletion
   and child-node creation are authorised against the **node owner**. After delegation diverges,
   record-create and record-delete in the same node answer to different principals. Worse, a node
   with no corresponding parent record (direct `cns-create-node`, or record deleted) has
   `-controller` = nil, so *nobody* — including the node owner — can create records in it.
2. **Orphaned nodes are permanently undeletable.** `cns-delete-node` checks the owner of the
   *parent* node (`*scope*`). Deleting a node removes its `cns-owners` entry, so surviving
   descendant nodes can never be deleted by anyone — not even their own owners. The registry pays
   memory for them forever. (Non-recursive deletion itself is by design; the defect is only the
   missing self-delete.)
3. **No user-level `delete`.** Records can only be removed via a raw scoped SPI call.
4. **Empty path segments are accepted.** `(symbol "etest.")` creates a record named `""` —
   unreachable by well-formed symbol paths. CAD014 now recommends nodes reject names that do not
   round-trip through the symbol representation.
5. **Minor.** `cns-delete-node` declares an unused `owner` parameter; `:ARGMENT` typo in
   `-controller`'s error (practically unreachable branch); `^{:private? true}` vs `^{:private true}`
   metadata inconsistency — note `Keywords.PRIVATE_META` (`:private`) is declared but not enforced
   anywhere in the CVM, so environment privacy is currently aspirational either way.

Not defects (intentional, now documented in CAD014): `control` not transferring node ownership;
non-recursive node deletion; segment syntax being node-defined; the `convex.cns` → root alias.

## Proposed upgrade: `v1-registry.cvx`

A `CodeMigration` applied to `#9`, added to `Migrations.Bootstrap` alongside `v1-core.cvx` /
`v1-metadata.cvx` / `v1-fungible.cvx`. Pure code redefinition — no data migration required
(every existing node already has a `cns-owners` entry, created either at genesis or by
`cns-create-node`).

Proposed contents, in decreasing order of confidence:

1. **Unify node-content authority on the node owner** (fixes defect 1). Redefine `cns-write` so
   the new-record check is `(trust/trusted? (get cns-owners *scope*) *caller* :create pname)`,
   replacing the `-controller` lookup; `-controller` is removed (also removing the `:ARGMENT`
   typo). Root behaviour is unchanged (`cns-owners` maps `[]` to the root controller). This is a
   **permission-semantics change** on networks where record controller and node owner have
   diverged; believed to be no-one on Protonet, but the release notes must state it.
2. **Allow node self-deletion** (fixes defect 2). Redefine `cns-delete-node` to authorise if the
   caller is trusted by the parent node's owner **or** by the target node's own owner
   (`(get cns-owners (conj *scope* pname))`). Keeps the `[pname owner]` signature for caller
   compatibility (parameter documented as reserved). This lets owners of orphaned subtrees clean
   them up; it does not introduce recursive deletion.
3. **Add a user-level `delete`** (fixes defect 3): resolves the parent node from the path and
   issues the SPI delete, mirroring `create`'s traversal. Re-enables the commented-out
   `testDelete` cases in `CNSTest`.
4. **Reject empty segments in the user API** (fixes defect 4): `-check` fails with `:ARGUMENT`
   if any segment is empty. SPI intentionally left unrestricted — segment policy stays
   node-defined per CAD014. *(Optional — decide before scheduling; cheap and non-breaking for any
   well-formed existing name.)*
5. **Metadata hygiene**: standardise on `^:private` in redefined bindings. *(Cosmetic; rides along
   at zero cost.)*

Explicitly **not** proposed: recursive deletion (breaks shared subtrees), SPI-level charset
restrictions (node-defined policy), record-shape changes, any change to resolution semantics.

### Migration mechanics and testing

- The migration is a resource `/convex/migrations/v1-registry.cvx` evaluated in the context of
  `#9`, exactly like `v1-fungible.cvx` is for the fungible library — except `#9` is a static
  address, so no CNS self-lookup is needed.
- Order within Bootstrap: after the core fixes (the registry code uses only stable core functions,
  so ordering is not semantically critical, but keeping library fixes last matches the existing
  pattern).
- Tests: `CNSTest.testDelegatedControlTransfer` and `testNodeDeletionOrphans` currently pin the
  pre-upgrade behaviours with comments marking the open issues. The migration PR must flip those
  assertions when running against `BaseTest.UPGRADED`, add positive tests for `delete` and orphan
  self-deletion, and satisfy the standard gating policy (changes move the post-upgrade state hash,
  which is expected and correct for migration content; genesis replay hash must be untouched).
- Per UPGRADE.md's "all known bugs in one upgrade" principle, this should land **before v1 is
  scheduled on any network**.

## User namespaces (separate track — no protocol upgrade needed)

CAD014 plans `user`, `id`, `app`, `lab` and `peer` root namespaces. These need **no migration**:
a new actor implementing the node SPI (`cns-read` / `cns-write` / `cns-create-node` /
`cns-delete-node` / `check-trusted?`) plus purchase logic can be deployed by an ordinary
governance transaction that then creates the root entry (as `Init.addCNSBaseTree` does at
genesis). Design questions to settle in CAD014 before implementation: pricing model, whether
names are transferable assets (CAD019 integration), expiry/renewal, and squatting mitigation.
The `convex/user.cvx` and `app/names.cvx` stubs are placeholders for this work. Deploy to testnet
first per the current testnet strategy.

## Open questions

- Should `create` auto-creating intermediate nodes assign them the *final record's* controller
  (current behaviour) rather than the caller? Convenient, but callers may not intend to hand
  intermediate namespaces to the target controller. Candidate for the same v1 bundle if changed.
- `create`'s return value is undefined (`nil`); `[#9 path]` would be more useful.
- Should `:private` environment metadata ever be enforced by the CVM (e.g. blocking cross-account
  env lookup)? Currently declared (`Keywords.PRIVATE_META`) but unused. If not, drop the
  annotations from the sources at the next opportunity.
- Whether `convex.cns` should remain a root alias or point to a dedicated CNS-tooling namespace
  once alternative roots exist.
