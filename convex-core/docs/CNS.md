# CNS Implementation Notes

The Convex Name System maps dotted names such as `convex.asset` to addresses and
values. [CAD014](https://docs.convex.world/docs/cad/cns) is the normative
specification: user API, record and node model, authority model and root namespaces.
This document is the implementation companion: where the pieces live, how the
registry stores its data, why the registry code is consensus-frozen, and the registry
changes planned for the protocol v1 upgrade.

## Key points

- The standard registry actor is genesis state at `#9` (`*registry*`), with the trust
  library at `#10`. Editing their sources changes the genesis hash, so every change to
  `#9` ships as a migration, never as a source edit (see [UPGRADE.md](UPGRADE.md)).
- Resolution walks the path one segment at a time through the node SPI, so any actor
  implementing the SPI can host a subtree. Reads run inside `query`, so node code cannot
  mutate state during resolution.
- Two capabilities are deliberately separate: record controllers govern records
  (`:update`); node owners govern namespace structure (`:create`, `:delete`,
  `:control`). Transferring a name and its subtree therefore takes two operations.
- The registry has known deviations from CAD014 (record-creation authority, orphaned
  nodes, no user-level `delete`, unvalidated empty segments). They are fixed by a
  registry migration bundled into protocol v1, which is not yet written.
- New root namespaces (`user`, `id`, `app`, `lab`, `peer`) need no protocol upgrade: a
  new SPI actor plus a governance transaction creating the root entry suffices.

## Implementation map

| Component | Location | Notes |
|---|---|---|
| Registry actor | `convex-core/src/main/cvx/convex/core/registry.cvx` | Deployed at `#9` during genesis. Genesis source: consensus-frozen. |
| Trust library | `convex-core/src/main/cvx/convex/core/trust.cvx` | Deployed at `#10`; the registry locates it as the next address after its own. Genesis source. |
| Genesis CNS tree | `Init.addCNSBaseTree`, `Init.doActorDeploy`, `Init.doCurrencyDeploy` | Creates `convex.*`, `asset.*`, `torus.*`, `currency.*`, the `init` record and the `convex.cns` alias. |
| Java resolution | `Context.lookupCNS`, `Context.lookupCNSRecord`, `State.lookupCNS` | Actor-calls `resolve` and `read` on `#9`. Used by the REST API, GUI, MCP server and the v1 bootstrap migration. |
| CVM resolution | `resolve` core macro, `@` reader syntax, `import` | All route through the registry's `resolve`. |
| Code generation | `Code.cnsUpdate` | Emits `(#9/create 'name addr controller)` for genesis deploys. |
| Tests | `convex.lib.CNSTest`, `convex.actors.RegistryTest` | `CNSTest` pins the current authority behaviour, including the deviations the v1 migration changes. |
| Purchasable-name stubs | `convex/user.cvx`, `app/names.cvx` | Placeholders for the user-namespace track. |

## Data model

The registry holds two top-level structures:

- `cns-database`: map of path vector to a node map of segment name to
  `[value controller metadata child]`. Each key is a **node**, each entry a **record**;
  the root node is `[]`. A record's `child`, when non-nil, is a scoped reference to the
  node holding its children, `[#9 path]` for nodes the registry hosts itself.
- `cns-owners`: map of path vector to the node owner, a trust monitor governing
  namespace structure.

Resolution issues one scoped `(call ref (cns-read pname))` per segment from the root.
Subtree nodes are independently owned and may be shared between records, which is why
node deletion is deliberately non-recursive.

## Consensus constraint

`registry.cvx` and `trust.cvx` are genesis sources. Editing them changes the genesis
hash and the replay-from-source state hash, breaking identity with live networks. Per
[UPGRADE.md](UPGRADE.md), genesis is never modified: any change to account `#9`, down
to a string typo, ships as a `CodeMigration` in `Migrations.Bootstrap` or a later
version. Java-side code outside the state transition (`Context.lookupCNS`), lab code,
tests and docs are unconstrained.

While protocol v1 is unscheduled its bootstrap migration is still editable, so registry
fixes can ride in it at no cost. Once v1 activates on any network, further fixes need a
v2 upgrade.

## Known deviations from CAD014

CAD014 lists the deviations under its implementation status. In summary:

1. **Record-creation authority** is checked against the parent record's controller,
   while deletion and child-node creation are checked against the node owner. After
   delegation diverges the two answer to different principals, and a node with no parent
   record has no principal able to create records at all.
2. **Orphaned nodes are undeletable.** Deleting a node removes its `cns-owners` entry,
   so surviving descendants can never be deleted, even by their own owners.
3. **No user-level `delete`.** Records can only be removed through a raw scoped SPI
   call.
4. **Empty segments are accepted**, producing records unreachable by well-formed
   symbol paths.
5. Minor: an unused `owner` parameter on `cns-delete-node`, an `:ARGMENT` typo in an
   error, and inconsistent `:private` metadata (which the CVM does not enforce anyway).

Intentional and documented in CAD014, not defects: `control` does not transfer node
ownership; node deletion is non-recursive; segment syntax is node-defined; `convex.cns`
aliases the root.

## Planned v1 registry migration

A `CodeMigration` applied to `#9` alongside the other v1 library migrations under
`convex-core/src/main/cvx/convex/migrations/`. Pure code redefinition; no data
migration is needed because every node already has a `cns-owners` entry. Not yet
implemented. Proposed contents, in decreasing order of confidence:

1. **Unify node-content authority on the node owner.** `cns-write` checks
   `(trust/trusted? (get cns-owners *scope*) *caller* :create pname)` for new records;
   the parent-controller lookup goes away. Root behaviour is unchanged. This is a
   permission-semantics change wherever controller and owner have diverged; release
   notes must say so.
2. **Allow node self-deletion.** `cns-delete-node` authorises if the caller is trusted
   by the parent node's owner or by the target node's own owner. Same signature; no
   recursive deletion.
3. **Add a user-level `delete`** mirroring `create`'s traversal, re-enabling the
   disabled delete cases in `CNSTest`.
4. **Reject empty segments in the user API** (`-check` fails with `:ARGUMENT`); the SPI
   stays unrestricted so segment policy remains node-defined.
5. **Metadata hygiene**: standardise on `^:private` in redefined bindings.

Explicitly not proposed: recursive deletion, SPI-level charset rules, record-shape
changes, or any change to resolution semantics.

The migration PR must flip the pinned assertions in `CNSTest` when running against
`BaseTest.UPGRADED`, add positive tests for `delete` and orphan self-deletion, and
leave the genesis replay hash untouched. It should land before v1 is scheduled on any
network.

## User namespaces

CAD014 plans `user`, `id`, `app`, `lab` and `peer` root namespaces. These need no
migration: a new actor implementing the node SPI (`cns-read`, `cns-write`,
`cns-create-node`, `cns-delete-node`, `check-trusted?`) plus purchase logic is deployed
by an ordinary governance transaction that then creates the root entry. Open design
questions belong in CAD014: pricing, whether names are transferable assets
([CAD019](https://docs.convex.world/docs/cad/assets)), expiry and renewal, squatting.

## Open questions

- Should `create` assign auto-created intermediate nodes to the final record's
  controller (current behaviour) rather than the caller?
- Should `create` return `[#9 path]` instead of `nil`?
- Should `:private` environment metadata ever be enforced by the CVM? If not, drop the
  annotations from the sources.
- Should `convex.cns` remain a root alias once alternative roots exist?

## Related

- [CAD014 Convex Name System](https://docs.convex.world/docs/cad/cns) — normative specification.
- [CAD022 Trust Monitors](https://docs.convex.world/docs/cad/trustmon) — the authority primitive the registry uses.
- [UPGRADE.md](UPGRADE.md) — why registry changes ship as migrations.
- `cns` skill under `.claude/skills/` — using CNS from the CLI.
