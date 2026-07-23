---
name: memory
description: Memory accounting and allowances — the cost of on-chain storage, and how to minimise and reclaim it. Use when reasoning about state growth, diagnosing a :MEMORY failure, or designing actors that store data.
---

# Memory Accounting

Memory prices **storage**. Unlike juice, it is a stock: held as an allowance,
consumed when state grows, and **refunded when state shrinks**. Computation is
priced separately — see the `juice` skill.

On-chain storage is potentially permanent, and every peer bears it. Memory
accounting exists so that whoever creates that burden accounts for it.

Normative spec: `https://docs.convex.world/docs/cad/memory`.

## Storage Size

```
storage size = 64 + (bytes of the cell's own encoding) + (memory size of child cells)
```

Two consequences worth internalising:

- **The 64-byte constant is per non-embedded cell.** Cell *count* costs, not
  just bytes. A structure split across many small cells is far more expensive
  than the same data embedded.
- **Embedded cells have a memory size of zero.** Their bytes are already inside
  the parent's encoding. Embedding is genuinely free storage — see the
  `cad3-encoding` skill for the 140-byte embedding limit.

## Consumption

Measured per transaction, at the end:

```
Memory Consumption = state size at end − state size at start
```

When consumption is positive, resolved in this order:

1. Deduct from the user's **memory allowance**, if sufficient.
2. Otherwise **buy from the memory pool**, paying at most
   `remaining juice × juice price`. This is where memory and juice meet — a
   transaction can fail for memory because it spent its juice elsewhere.
3. Otherwise fail with **`:MEMORY`**, roll back all state changes, and still
   charge the juice.

When consumption is **negative, the allowance is refunded** by the amount
released. Freeing storage pays.

## Minimise Allocation

Treat on-chain storage as the scarcest thing you are spending. In order of
leverage:

- **Embed rather than branch.** Small values inside a parent encoding cost
  nothing extra; each separate cell costs 64 bytes of overhead before its
  content.
- **Keep cell counts low.** Prefer one compact structure over many small ones.
- **Store the minimum that satisfies the requirement.** Derive what can be
  derived, and keep off-chain what does not need consensus. Storing a hash or a
  reference is usually enough when the payload itself need not be on-chain.
- **Do not store what a query can compute.** On-chain caching of derived values
  trades permanent storage for transient compute — usually the wrong way round.
- **Actors should allocate sparingly when called by users.** The *caller* pays
  for what your actor allocates, so a wasteful actor makes every interaction
  with it expensive. This is a competitive property, not just good manners.

Note that de-duplication does not help the allocating user: identical
encodings are stored once network-wide, but you still pay allowance for what
you allocate. Do not design around it.

## Reclaim Aggressively

Every byte released is allowance refunded, so cleaning up is directly
rewarded — for users and actors alike.

- **Delete data you no longer need.** Definitions in your own account
  environment are yours to remove, and safe to remove if you hold backups —
  the data can always be restored later.
- **Give actors clean-up functions.** A well-designed actor lets participants
  remove what is finished with: read messages, filled orders, zero-balance
  holder records, expired offers, de-registrations. Without such a function the
  storage is stranded permanently.
- **Expect callers to use them.** The party who cleans up claims the refund, so
  clean-up paths get used. That is the intended incentive — a responder can
  come out ahead by tidying up after an interaction.
- **Give actors a way to reclaim their own allowance.** Actors hold allowances
  but normally do not spend them: the transaction *origin* pays. The exception
  is **scheduled execution**, where the actor is itself the origin. An
  allowance that accumulates in an actor with no reclaim path is unreachable
  forever, and cannot be retrofitted without an upgrade.

Allowances may also be transferred directly between accounts, which is how
actors and multi-account users manage them without allocate/deallocate tricks.

## The Memory Pool

An automated market maker. Seeded at genesis with 1,000,000 bytes against
1,000 Convex Coins (~1 Coin/KB), and grown at a fixed rate (currently 1 MB per
day).

Growth is deliberate: it avoids a hard supply ceiling and penalises hoarding,
since new supply dilutes accumulated allowances.

## Implementation Notes

Memory size is computed lazily and **cached per cell**; peers MUST cache it to
meet performance requirements. Cells are immutable, so a cached size is never
invalidated — that is what keeps accounting O(1) per allocated cell.

The accounting subsystem is designed to cause **no net cell allocations
itself**, so it cannot drive the state growth it exists to control. Preserve
that if you change it: update embedded fields on existing cells rather than
allocating new ones.
