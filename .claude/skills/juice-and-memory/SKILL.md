---
name: juice-and-memory
description: Convex resource accounting — juice costs and memory allowances. Use when reasoning about transaction costs, :JUICE or :MEMORY failures, state growth, or the cost of a CVM operation.
---

# Juice and Memory Accounting

Convex meters two distinct resources. They are separate systems that interact
at one specific point, and confusing them is the usual source of error.

- **Juice** prices *computation and bandwidth* — a flow, paid per transaction.
- **Memory** prices *storage* — a stock, held as an allowance and refunded when
  released.

Specs: `https://docs.convex.world/docs/cad/juice` and `.../cad/memory`.

## Juice

```
Juice Fees    = Juice Consumed × Juice Price
Juice Consumed = Transaction Size Cost + Σ(cost of each operation)
Transaction Size Cost = TRANSACTION_PER_BYTE × storage size of the transaction
```

`TRANSACTION_PER_BYTE` is 20, so **transaction size is itself a cost** — an
incentive to keep submitted transactions small.

Every transaction carries a **juice allowance**, capped at 10,000,000 and
bounded by the origin account's ability to pay. Juice price is in the CVM state
and readable as `*juice-price*`; it rises with load and decays towards its
floor of 1 (roughly a six-second half-life at zero load).

**On `:JUICE` failure the origin is charged the full allowance and every effect
is rolled back.** Running out of juice is not free.

The CVM must check juice *before* performing any O(n) work, so an attacker
cannot buy O(n) computation with less than O(n) of committed juice. If you add
an operation whose cost scales with input size, it needs a juice cost that
scales too — that check is a security property, not an optimisation.

## Memory

Memory accounting solves state growth: storage is potentially permanent, so
someone must account for it.

**Storage size of a cell:**

```
64 + (bytes of the cell's own encoding) + (memory size of child cells)
```

The 64-byte constant approximates per-cell storage overhead. **Embedded cells
have a memory size of zero** — their bytes are already counted inside the
parent's encoding. This is a direct incentive to embed rather than branch; see
the `cad3-encoding` skill.

**Memory consumption** is measured per transaction:

```
Memory Consumption = state size at end − state size at start
```

Resolution order when consumption is positive:

1. Deduct from the user's **memory allowance**, if sufficient.
2. Otherwise **automatically buy** memory from the pool, paying at most
   `remaining juice × juice price`. *This is where the two systems meet* — a
   transaction can fail for lack of memory because it spent its juice
   elsewhere.
3. Otherwise fail with **`:MEMORY`**, roll back all state changes, and still
   charge the juice.

Negative consumption **refunds** allowance — releasing storage pays you back.
That is deliberate: it is why well-designed actors offer clean-up functions,
and why deleting stale data is worth doing rather than merely tidy.

## The Memory Pool

An AMM, seeded at genesis with 1,000,000 bytes against 1,000 Convex Coins
(~1 Coin/KB) and growing at a fixed rate (currently 1 MB/day). Allowances may
be transferred directly between accounts.

Growth is intentional: it prevents a hard supply ceiling and penalises
hoarding.

## Actor Allowances

Actors hold allowances but usually do not use them — the *origin* of the
transaction pays. The exception is **scheduled execution**, where an actor is
itself the origin.

An allowance stranded in an actor is unreachable unless the actor was written
with a way to reclaim it. If you are writing an actor that may accumulate
allowance, add that reclaim path; there is no way to retrofit it after deploy
without an upgrade.

## Implementation Notes

Memory size is computed lazily and **cached per cell**; peers MUST cache it to
meet performance requirements. Because cells are immutable, a cached size is
never invalidated — which is what keeps accounting O(1) per allocated cell.

Memory accounting is designed to cause no net cell allocations itself, so the
accounting cannot itself drive state growth. Preserve that when changing it:
update embedded fields on existing cells, do not allocate.
