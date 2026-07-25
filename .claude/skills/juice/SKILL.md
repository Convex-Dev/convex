---
name: juice
description: Juice accounting — the cost of computation and bandwidth on the CVM. Use when reasoning about transaction execution cost, diagnosing a :JUICE failure, or pricing a new CVM operation.
---

# Juice Accounting

Juice prices **computation and bandwidth**. It is a flow: consumed and paid per
transaction, never held. Storage is priced separately — see the `memory` skill.

Normative spec: `https://docs.convex.world/docs/cad/juice`.

## The Calculation

```
Juice Fees     = Juice Consumed × Juice Price
Juice Consumed = Transaction Size Cost + Σ(cost of each operation executed)

Transaction Size Cost = TRANSACTION_PER_BYTE × storage size of the transaction
```

`TRANSACTION_PER_BYTE` is 20, so **the size of the submitted transaction is
itself a cost**, independent of what it does. Compact transaction source is
cheaper source.

## Allowance

Every transaction carries a **juice allowance**:

- Specified by the user, or the maximum available if unspecified
- Capped at **10,000,000**, bounding the cost of any single transaction
- May not exceed what the origin account can actually pay

**On a `:JUICE` failure the origin is charged the full allowance and every
state change is rolled back.** Running out is not free — it is the most
expensive way for a transaction to fail. When execution cost is uncertain,
estimate against a local network before submitting.

## Price

Juice price lives in the CVM state and is readable from CVM code as
`*juice-price*`. It moves with network load:

- Rises when sustained load exceeds `JUICE_PER_SECOND` (100,000,000)
- Decays towards its floor when load is lighter — roughly a six-second half
  life at zero load
- Has a hard minimum of **1**; juice is never free

The genesis price is 2 and the scale factor is 1.125. Governance may update the
scale factor and throughput constant.

This is the cryptoeconomic defence: sustaining an attack means paying
exponentially rising prices, and a burst while prices are low can only delay
confirmation, not exclude legitimate transactions.

## Pricing New Operations

If you add a CVM op or runtime function, it needs a juice cost.

- Every op MUST have a **fixed positive** cost — nothing executes free.
- An op whose work scales with input size MUST have a cost that scales too.
- The CVM MUST check sufficient juice **before** performing O(n) work, and
  raise `:JUICE` if it is not there.

That ordering is a security property, not an optimisation. If an attacker can
trigger O(n) work having committed less than O(n) of juice, the asymmetry is a
denial-of-service vector. Cost should track an upper bound on compute time,
storage size or bandwidth — whichever dominates.

## Relationship to Memory

A transaction short of memory allowance can buy memory from the pool using its
**remaining juice** — so juice exhaustion can surface as a `:MEMORY` failure
and vice versa. See the `memory` skill for that interaction.
