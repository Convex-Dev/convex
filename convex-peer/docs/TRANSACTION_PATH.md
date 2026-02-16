# Transaction Pipeline

End-to-end path of a client transaction through a Convex peer server, from network
arrival to result return.

## Pipeline Overview

```
Client                          Peer Server
  │
  │  conn.sendMessage(m)
  ▼
┌──────────────────┐
│ Netty I/O Thread  │  decode bytes → Message
│                   │  server.deliverMessage(m)
│                   │  processMessage(m) → txMessageQueue.offer()
└────────┬─────────┘
         │                              txMessageQueue
         │                         ArrayBlockingQueue (10,000)
         ▼
┌──────────────────┐
│ TransactionHandler│  poll(10ms) + sleep(1ms) + drainTo
│    (single thread)│  Phase 1: extract + cheap checks
│                   │  Phase 2: parallel sig verify (SIGN_POOL)
│                   │  Phase 3: offer to transactionQueue + registerInterest
└────────┬─────────┘
         │                              transactionQueue
         │                         ArrayBlockingQueue (30,000)
         ▼
┌──────────────────┐
│ BeliefPropagator  │  awaitBelief(): poll beliefQueue for 30ms     ◄── WAIT
│    (single thread)│  maybeGenerateBlocks():
│                   │    transactionQueue.drainTo()
│                   │    Block.create (max 1024 txns each)          ◄── BOUNDED
│                   │    sign + persist each block
│                   │  belief.proposeBlock()
│                   │  maybeBroadcast()
│                   │  Cells.persist(belief)
│                   │  server.updateBelief(belief) → executor.queueUpdate()
└────────┬─────────┘
         │                          LatestUpdateQueue<Belief>
         │                         (single-slot, overwrites)
         ▼
┌──────────────────┐
│ CVMExecutor       │  poll(100ms) for belief update                ◄── WAIT
│    (single thread)│  peer.updateState()
│                   │    validateSignatures (parallel)
│                   │    applyBlock → applyTransactions (sequential)
│                   │  persistPeerData()
│                   │  maybeReportTransactions()
│                   │    interests.get(hash) → m.returnResult(res)
└────────┬─────────┘
         │
         ▼
Client receives Result
```

## Stage 1: Network Ingress

**Thread:** Netty worker event loop

1. Decode bytes → `Message`
2. `server.deliverMessage(m)` — decode payload, observe
3. `processMessage(m)` — route by type, TRANSACT → `txMessageQueue.offer()`
4. If queue full: return `txnRetry` predicate → backpressure (virtual thread blocks)

**Queue:** `txMessageQueue` — `ArrayBlockingQueue(10,000)`

## Stage 2: TransactionHandler

**Thread:** Single virtual thread

### Loop Timing

```java
Message m = txMessageQueue.poll(BLOCKTIME, TimeUnit.MILLISECONDS);  // 10ms timeout
Thread.sleep(1);                                                     // batch coalesce
messages.add(m);
txMessageQueue.drainTo(messages);                                    // drain rest
processMessages();
```

**Per iteration:** 10ms poll timeout + 1ms sleep + processing time

### Three-Phase Processing

| Phase | Work | Cost |
|-------|------|------|
| 1. Extract + cheap checks | Format, account, sequence, key match | ~5us/txn |
| 2. Parallel sig verify | `Peer.preValidateSignatures()` on SIGN_POOL | ~70us/txn ÷ N cores |
| 3. Cache check + queue | `sd.checkSignature()` (cached), `transactionQueue.offer()`, `registerInterest()` | ~1us/txn |

**Output queue:** `transactionQueue` — `ArrayBlockingQueue(30,000)`

If `transactionQueue.offer()` fails → return `:LOAD` immediately (non-blocking).

### Interest Registration

```java
interests.put(sd.getHash(), m);   // HashMap<Hash, Message>
```

Maps transaction hash → original Message so results can be returned later.

## Stage 3: BeliefPropagator

**Thread:** Single virtual thread

### Loop Structure

```java
protected void loop() throws InterruptedException {
    Belief incomingBelief = awaitBelief();           // BLOCKS up to 30ms
    boolean updated = maybeUpdateBelief(incomingBelief);
    maybeBroadcast(updated);
    belief = Cells.persist(belief, server.getStore());
    server.updateBelief(belief);                     // → executor.queueUpdate()
}
```

### awaitBelief()

```java
Message firstEvent = beliefQueue.poll(AWAIT_BELIEFS_PAUSE, TimeUnit.MILLISECONDS);
```

**`AWAIT_BELIEFS_PAUSE = 30ms`** — waits for incoming peer beliefs. On a single-peer
network with no remote peers, this **always waits the full 30ms** before returning
null and proceeding to block creation.

### maybeGenerateBlocks()

Called from `maybeUpdateBelief()`. Drains `transactionQueue` and creates blocks:

```java
if (timestamp < lastBlockPublishedTime + minBlockTime) return null;  // 10ms guard

transactionQueue.drainTo(newTransactions);
if (ntrans == 0) return null;

int maxBlockSize = Constants.MAX_TRANSACTIONS_PER_BLOCK;   // 1024
int nblocks = ((ntrans - 1) / maxBlockSize) + 1;

for (int i = 0; i < nblocks; i++) {
    Block block = Block.create(timestamp, newTransactions.subList(start, end));
    SignedData<Block> signedBlock = peer.getKeyPair().signData(block);  // Ed25519 sign
    signedBlock = Cells.persist(signedBlock, server.getStore());        // persist
    signedBlocks[i] = signedBlock;
}
```

**Constraints:**
- **`minBlockTime = 10ms`** — won't create blocks more often than every 10ms
- **`MAX_TRANSACTIONS_PER_BLOCK = 1024`** — each block holds at most 1024 transactions
- Each block is signed (Ed25519) and persisted to store
- For 27,000 transactions → 27 blocks signed + persisted in one call

### Belief Update

```java
belief = belief.proposeBlock(server.getKeyPair(), signedBlocks);
```

Adds all blocks to the peer's Order in the Belief. Single peer → immediate consensus
at all levels.

### Trigger CVM Executor

```java
server.updateBelief(belief);  // → executor.queueUpdate(belief)
```

`LatestUpdateQueue` — single-slot queue that **overwrites** previous value. If the
executor hasn't polled yet, the old belief is replaced. `offer()` calls `notify()` to
wake the executor's `poll(timeout)`.

## Stage 4: CVMExecutor

**Thread:** Single virtual thread

### Loop Structure

```java
Belief beliefUpdate = update.poll(100, TimeUnit.MILLISECONDS);  // BLOCKS up to 100ms

synchronized(this) {
    if (beliefUpdate != null) {
        peer = peer.updateBelief(beliefUpdate);
    }
    Peer updatedPeer = peer.updateState();       // execute blocks
    if (updatedPeer != peer) {
        peer = updatedPeer;
        persistPeerData();                        // write to store
    }
}

server.transactionHandler.maybeReportTransactions(peer);
```

**`update.poll(100ms)`** — waits for belief update from BeliefPropagator. The
`LatestUpdateQueue.offer()` calls `notify()`, so the executor wakes immediately when a
belief is queued — the 100ms is just the idle timeout.

### Result Reporting

```java
long newConsensusPoint = peer.getFinalityPoint();
if (newConsensusPoint > reportedConsensusPoint) {
    for (long i = reportedConsensusPoint; i < newConsensusPoint; i++) {
        SignedData<Block> block = peer.getPeerOrder().getBlock(i);
        if (block.getAccountKey().equals(peer.getPeerKey())) {
            BlockResult br = peer.getBlockResult(i);
            reportTransactions(block.getValue(), br, i);
        }
    }
    reportedConsensusPoint = newConsensusPoint;
}
```

For each new finalised block:
1. Look up `interests.get(txHash)` → original Message
2. `m.returnResult(res)` → sends result back to client via Netty

## Timing Analysis

### Best Case (single peer, small batch)

| Stage | Latency | Notes |
|-------|---------|-------|
| Network → txMessageQueue | ~1ms | Netty decode + offer |
| txMessageQueue wait | 0-11ms | poll(10ms) + sleep(1ms) |
| TransactionHandler processing | 1-30ms | Depends on batch size, parallel sigs |
| transactionQueue wait | 0-30ms | **Waits for BeliefPropagator loop** |
| BeliefPropagator awaitBelief | **up to 30ms** | Polls beliefQueue, no peers = full wait |
| Block creation + sign + persist | 1-10ms | Per block: sign (~70us) + persist |
| CVMExecutor wake | ~0ms | `notify()` from `queueUpdate()` |
| CVM execution | varies | State application |
| Result return | ~1ms | Interest lookup + Netty write |
| **Total** | **~30-80ms typical** | |

### Bottleneck: BeliefPropagator Loop Period

The BeliefPropagator loop runs roughly every **30ms + processing time**:

```
awaitBelief()     30ms wait (no remote beliefs on single peer)
maybeUpdateBelief()  block creation + signing
maybeBroadcast()     broadcast to peers
persist + update     persistence + trigger executor
─────────────────────────────────────────────────────
Total:            ~35-50ms per loop iteration
```

**Throughput limit from this loop:**
- Each iteration drains `transactionQueue` completely
- Creates blocks of max 1024 txns each
- At ~40ms per iteration → ~25 iterations/sec
- At 10,000 txns per drain → ~250,000 txns/sec theoretical
- But limited by block count: 1024 txns/block × 27 blocks = signing + persisting 27 blocks

### What Happens at 27,000 Transactions

```
T=0ms      27,000 transactions arrive (63ms send time)
T=0-11ms   TransactionHandler polls first batch
T=12ms     sleep(1ms), drain ~10,000 from txMessageQueue
T=13-30ms  Phase 1-3: validate + queue to transactionQueue
T=30ms     Second batch from txMessageQueue...
T=30-60ms  BeliefPropagator: awaitBelief() waiting 30ms (no beliefs!)
T=60ms     maybeGenerateBlocks() drains transactionQueue
           Gets first ~10,000-20,000 transactions
           Creates 10-20 blocks, each signed + persisted
T=70ms     proposeBlock, broadcast, persist, updateBelief
T=70ms     CVMExecutor wakes, processes 10-20 blocks
T=90ms     Second BP loop: awaitBelief() waits ANOTHER 30ms
T=120ms    maybeGenerateBlocks() drains remaining transactions
           ...
```

**Key issue:** Each BeliefPropagator loop iteration starts with a **30ms wait** even
when `transactionQueue` has pending transactions. With 27,000 transactions needing
multiple BP loop iterations, the cumulative wait adds up.

## Block Batching Rationale

### Why Not Create a Block Per Transaction?

Without batching, a peer receiving a steady stream of transactions would create a
separate block for each one. Every block carries fixed overhead:

- **Ed25519 signing** (~70us) — each block must be signed by the peer
- **Persistence** — each signed block is written to the store
- **Network broadcast** — the belief delta includes each block separately
- **Consensus overhead** — each block is a separate entry in the peer's Order

A block holding 1 transaction costs the same signing + persistence overhead as a
block holding 1024 transactions. Batching amortises this overhead across many
transactions, dramatically improving throughput.

### Two Delay Mechanisms

Two separate mechanisms control how transactions accumulate before block creation:

| Mechanism | Value | Location | Purpose |
|-----------|-------|----------|---------|
| `AWAIT_BELIEFS_PAUSE` | 30ms | `BeliefPropagator.awaitBelief()` | Wait for incoming peer beliefs before merge |
| `minBlockTime` | 10ms | `TransactionHandler.maybeGenerateBlocks()` | Minimum interval between block publications |

**`minBlockTime` (10ms)** is the explicit batching guard. It prevents block creation
more often than every 10ms, ensuring transactions accumulate in `transactionQueue`
between calls. This is the correct mechanism for batching — it's configurable via
`:min-block-time` and directly controls block publication rate.

**`AWAIT_BELIEFS_PAUSE` (30ms)** is designed for multi-peer belief merging. In a
multi-peer network, beliefs arrive from remote peers and need to be accumulated
before merging to reduce the number of merge operations. However, because
`maybeGenerateBlocks()` is called from the same loop, this wait **also** controls
the maximum block creation frequency — the loop can't call `maybeGenerateBlocks()`
more often than once per `awaitBelief()` call.

### The Coupling Problem

These two concerns — peer belief merging and local block creation — are coupled in
the same loop:

```java
protected void loop() throws InterruptedException {
    Belief incomingBelief = awaitBelief();       // 30ms wait for peer beliefs
    boolean updated = maybeUpdateBelief(...);     // includes maybeGenerateBlocks()
    maybeBroadcast(updated);
    // persist + update
}
```

On a single-peer network (or when no remote beliefs arrive), `awaitBelief()` always
waits the full 30ms — even when `transactionQueue` has pending transactions ready for
block creation. The `minBlockTime` of 10ms would allow blocks every 10ms, but the
loop period of 30ms+ means blocks are only created every ~35-50ms in practice.

This means:
- The **effective batching delay** is 30ms (awaitBelief), not 10ms (minBlockTime)
- The `minBlockTime` guard is redundant when `awaitBelief` dominates the loop period
- Under burst load, transactions sit in `transactionQueue` for 30ms of dead time per
  iteration while the propagator waits for beliefs that will never arrive

## Analysis: Is This the Best Approach?

### Current Behaviour Under Load

With 27,000 transactions arriving over ~63ms:

1. **Iteration 1:** awaitBelief waits 30ms (no beliefs), drains ~15,000 txns,
   creates 15 blocks, broadcasts, persists. Total: ~40ms.
2. **Iteration 2:** awaitBelief waits **another 30ms**, drains remaining ~12,000
   txns, creates 12 blocks, broadcasts, persists. Total: ~35ms.
3. **Total:** ~75-80ms for two iterations, of which ~60ms is dead wait time.

The 30ms wait dominates. Reducing it would improve throughput significantly.

### Option A: Reduce AWAIT_BELIEFS_PAUSE

Simply lower the constant (e.g. to 5ms). Pros: trivial change. Cons: on multi-peer
networks, shorter wait means more frequent belief merges with fewer accumulated
beliefs — more merge operations, more broadcasts.

### Option B: Skip Wait When Transactions Are Pending

Check `transactionQueue` before waiting:

```java
private Belief awaitBelief() throws InterruptedException {
    // If transactions are waiting, don't block for remote beliefs
    long waitTime = server.transactionHandler.hasTransactions()
        ? 0
        : AWAIT_BELIEFS_PAUSE;
    Message firstEvent = beliefQueue.poll(waitTime, TimeUnit.MILLISECONDS);
    ...
}
```

This decouples the concerns: when there are pending transactions, block creation
happens immediately (subject to `minBlockTime`). When idle, the full 30ms wait
applies for belief accumulation. Pros: no impact on multi-peer belief merging when
idle. Cons: under sustained load the wait is always 0, so beliefs are merged one at
a time — but this is acceptable since local transactions should not be delayed for
remote belief batching.

### Option C: Separate Block Creation from Belief Merging

Move `maybeGenerateBlocks()` to a separate loop/signal that runs on its own schedule,
triggered when `transactionQueue` is non-empty and `minBlockTime` has elapsed. The
BeliefPropagator loop would only handle belief merging and broadcasting.

Pros: cleanest separation. Cons: more complex threading — need to coordinate between
the block creation trigger and belief update. The current design keeps all belief
mutation on a single thread, which avoids synchronisation issues.

### Option D: Notify-Based Wake

Add the `transactionQueue` as a signal source for `awaitBelief()`:

```java
// In TransactionHandler, after queuing transactions:
server.beliefPropagator.notifyTransactions();

// In BeliefPropagator:
private Belief awaitBelief() throws InterruptedException {
    Message firstEvent = beliefQueue.poll(AWAIT_BELIEFS_PAUSE, TimeUnit.MILLISECONDS);
    // poll returns early if notified via beliefQueue
    ...
}
```

By posting a synthetic message or using a shared condition variable, the propagator
wakes immediately when transactions are ready. Pros: minimal latency. Cons: adds
coupling between TransactionHandler and BeliefPropagator.

### Recommendation

**Option B** is the best balance of simplicity and effectiveness. It keeps all the
existing single-threaded guarantees, requires minimal code changes, and directly
addresses the problem: don't wait for beliefs when local transactions need
processing. The `minBlockTime` guard still prevents excessive block creation.

## Other Observations

### Block Size Limit

`MAX_TRANSACTIONS_PER_BLOCK = 1024` means 27,000 transactions require 27 blocks.
Each block must be independently signed (Ed25519, ~70us) and persisted to the store.
27 blocks × (sign + persist) adds measurable overhead per BP loop iteration.

### CVMExecutor LatestUpdateQueue

The `LatestUpdateQueue` holds only the **latest** belief. If the BeliefPropagator
produces two belief updates before the executor polls, the first is overwritten.
The executor still processes all blocks (since `updateState()` advances to the
consensus point), but intermediate beliefs are lost. This is by design for
convergence, but means the executor always processes the full delta in one go.

### Persistence Overhead

`persistPeerData()` is called after every state update. For large batches with many
blocks, this serialises and writes the full peer state. If the store is slow (e.g.
Etch on spinning disk), this adds latency between CVM execution and result reporting.

## Constants

| Constant | Value | Location | Purpose |
|----------|-------|----------|---------|
| `TRANSACTION_QUEUE_SIZE` | 10,000 | Config | txMessageQueue capacity |
| `transactionQueue` capacity | 30,000 | TransactionHandler | 3× txMessageQueue |
| `MAX_TRANSACTIONS_PER_BLOCK` | 1024 | Constants | Block size limit |
| `DEFAULT_MIN_BLOCK_TIME` | 10ms | TransactionHandler | Min interval between blocks |
| `AWAIT_BELIEFS_PAUSE` | 30ms | BeliefPropagator | Belief poll timeout |
| `BELIEF_QUEUE_SIZE` | 200 | Config | Incoming belief queue |
| `DEFAULT_CLIENT_TIMEOUT` | 8000ms | Config | Client gives up after this |
| `SIGN_CHUNK_SIZE` | 100 | Peer | Txns per parallel sig task |
| CVMExecutor poll timeout | 100ms | CVMExecutor | Idle belief poll |
