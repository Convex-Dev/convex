# Store Refactor: Remove ThreadLocal Stores

## Problem Statement

`Stores.current()` (a `ThreadLocal<AStore>`) is a major source of bugs, confusion, and
performance overhead across the Convex codebase. It creates implicit coupling between threads
and stores, makes error diagnosis difficult, and requires fragile save/restore patterns
wherever code crosses thread boundaries.

### Specific Issues

1. **Hidden state** -- the store a piece of code uses depends on which thread is running it
   and what happened to set the ThreadLocal on that thread. This is invisible at call sites.

2. **Cross-thread fragility** -- when callbacks or futures execute on different threads
   (Netty I/O, CompletableFuture continuations), the ThreadLocal may be null or point to the
   wrong store. This forces ugly save/capture/restore boilerplate (see `ConvexRemote`,
   `ConvexLocal`, `Connection`).

3. **Performance overhead** -- ThreadLocal access on every store operation, plus defensive
   save/restore patterns on hot paths (message receive, belief propagation).

4. **Timeout-masking bugs** -- when store context is missing or wrong, operations fail with
   `MissingDataException` which frequently manifests as timeouts rather than clear errors.

## Objectives

1. **Remove `Stores.current()` and all ThreadLocal store usage** from production code paths.
2. **Pass stores explicitly** where needed -- nodes (Server, Convex client) already hold their
   store as a field; thread components already have `getStore()`.
3. **Avoid unnecessary persistence** -- messages should be decoded in-memory where possible,
   with persistence under explicit control of the calling node.
4. **Maintain wire compatibility** -- no changes to CAD3 encoding, message format, or protocol.
5. **Improve error handling** -- fail fast with clear errors instead of silent timeouts.
6. **Keep existing tests passing** -- tests are correct; only change test assumptions after
   explicit discussion.

## Current Architecture

### ThreadLocal Store (`Stores.java`)

```
ThreadLocal<AStore> currentStore    -- per-thread store
Stores.current()                    -- get thread-local store (may be null!)
Stores.setCurrent(store)            -- set thread-local store
Stores.getGlobalStore()             -- fallback, creates temp EtchStore if unset
```

### Where ThreadLocal Is Used (33 call sites)

| Category | Sites | Examples |
|----------|-------|---------|
| Thread init (setCurrent at thread start) | 2 | `AThreadedComponent`, `AObserverQueue` |
| Save/restore in callbacks | 6 | `ConvexRemote.returnMessageHandler`, `ConvexLocal.makeResultHandler`, GUI code |
| Cross-thread store capture | 6 | `ConvexRemote.awaitResult`, `ConvexLocal.makeResultHandler` |
| Fallback for missing store | 3 | `Convex.acquire(Hash)`, `ConvexRemote.acquireState()` |
| Virtual thread setup | 1 | `Acquiror.getFuture()` |
| Data request handling | 1 | `ResultConsumer.handleDataRequest()` |
| Benchmarks / tests | ~14 | Various |

### Message Decode Flow (Store Dependency)

```
Network bytes --> NettyInboundHandler.decode()
  --> Message.create(returnHandler, null, Blob)     [UNDECODED -- no store needed]
  --> Server.receiveAction
    --> m.getPayload(getStore())                    [DECODED with server's store]
    --> processMessage(m)
```

Key insight: the **server already has a store field** (`Server.store`). The ThreadLocal is
redundant on server threads -- it was set to the same value at thread start in
`AThreadedComponent`.

### Client Decode Flow (The Hard Part)

```
ConvexRemote.message(m)
  --> awaitResult(id, timeout)
    --> captures Stores.current() as awaitingStore   [PROBLEM: may be null]
    --> future.handle((m, e) -> {
          Stores.setCurrent(awaitingStore);           [PROBLEM: restoring on wrong thread]
          m.getPayload(awaitingStore);                [uses captured store]
        })
```

The client needs a store for decoding results. Currently it captures the caller's ThreadLocal,
which is fragile. The fix: `Convex` subclasses should hold an explicit store field.

## Proposed Approach

### Phase 1: Storeless Decode for Client Code

**Key insight:** Client-side messages (query results, transaction results, status responses)
are complete -- all cells are included in the multi-cell encoding. They do not need a store.
`CAD3Encoder.INSTANCE.decodeMultiCell(data)` produces RefDirect trees: fast, no store
dependency, no GC pressure. MemoryStore should never be used in production code.

**Files:** `Message.java`, `Format.java`, `Convex.java`, `ConvexRemote.java`, `ConvexLocal.java`

- Add `Format.decodeMultiCell(Blob)` static convenience method (delegates to storeless encoder)
- Make `Message.getPayload()` (no-arg) attempt storeless decode for complete messages
- `ConvexRemote` -- remove all `Stores.current()` usage, use storeless decode for results
- `ConvexLocal` -- remove `Stores.current()` in `makeResultHandler`, use `server.getStore()`
  only where persistence is explicitly needed (e.g. `acquire`)
- `Acquiror` -- still needs a store for incremental acquisition (caller provides it)

Replace all ThreadLocal usage in client code:

| Current | Replacement |
|---------|-------------|
| `Stores.current()` in `awaitResult()` | Remove -- storeless decode via `m.getPayload()` |
| `Stores.setCurrent(awaitingStore)` in future handler | Remove entirely |
| `Stores.current()` / `setCurrent()` in `returnMessageHandler` | Remove entirely |
| `Stores.current()` in `makeResultHandler` | Remove -- storeless decode |
| `Stores.current()` in `acquire(Hash)` | Explicit store parameter (caller provides) |
| `Stores.current()` in `acquireState()` | Explicit store parameter |
| `Stores.setCurrent(store)` in `Acquiror` | Remove -- store already a field |

### Phase 2: Remove ThreadLocal from Server Components

**Files:** `AThreadedComponent.java`, `Server.java`, `BeliefPropagator.java`,
`TransactionHandler.java`, `QueryHandler.java`, `CVMExecutor.java`, `ConnectionManager.java`

- Remove `Stores.setCurrent(server.getStore())` from `AThreadedComponent.ComponentTask.run()`
- All components already use `getStore()` which delegates to `server.getStore()` -- no further
  changes needed in component code.
- `Server.receiveAction` already uses `getStore()` -- no change needed.

### Phase 3: Remove ThreadLocal from NIO Connection

**Files:** `Connection.java` (NIO impl)

- `sendChallenge()` / `sendResponse()` -- these use save/restore around message encoding.
  The connection should hold a store reference (or the messages should be pre-encoded).
  Since these are authentication messages that don't need store for encoding (they are
  fully self-contained `SignedData`), the ThreadLocal can simply be removed.
- `handleChannelReceive()` -- store context captured here is used for message decoding
  downstream. With the server's `receiveAction` already passing its own store, this capture
  is unnecessary.

### Phase 4: Remove ThreadLocal from GUI Code

**Files:** `BlockViewComponent.java`, `PeerGUI.java`

- Replace `Stores.current()` save/restore with direct `server.getStore()` references.

### Phase 5: Remove ThreadLocal from Observer

**Files:** `AObserverQueue.java`

- Remove `Stores.setCurrent(store)` -- pass store explicitly to observer operations.

### Phase 6: Clean Up `Stores.java`

**File:** `Stores.java`

- Deprecate (or remove) `current()` and `setCurrent()`
- Keep `getGlobalStore()` / `setGlobalStore()` for test convenience (these are not ThreadLocal)
- Consider removing entirely if no test code needs it

### Phase 7: Improve Error Handling

**Files:** `Server.java`, `NettyInboundHandler.java`, `ConvexRemote.java`

Across all message handling paths:

| Current Behaviour | Proposed Fix |
|-------------------|-------------|
| `MissingDataException` in `processMessage` silently logged | Return MISSING error Result to sender with missing hash |
| `BadFormatException` in `awaitResult` printed to stderr | Propagate as failed Result with FORMAT error code |
| Belief sync loops infinitely on timeout | Add max retry count, fail with clear TIMEOUT error |
| `getID()` on undecoded message throws `IllegalStateException` | Use `getResultID()` (already handles undecoded) consistently |
| `NettyInboundHandler` catches exception from `receiveAction` and tries `m.getID()` which may throw | Guard with try/catch, use `m.getResultID()` |

## File Change Summary

| File | Module | Change |
|------|--------|--------|
| `Convex.java` | convex-peer | Add `store` field, update `acquire()` |
| `ConvexRemote.java` | convex-peer | Use `this.store`, remove all ThreadLocal usage |
| `ConvexLocal.java` | convex-peer | Use `server.getStore()`, remove ThreadLocal |
| `Acquiror.java` | convex-peer | Remove `Stores.setCurrent()`, store already a field |
| `AThreadedComponent.java` | convex-peer | Remove `Stores.setCurrent()` |
| `Server.java` | convex-peer | Minor error handling improvements |
| `Connection.java` (NIO) | convex-peer | Remove save/restore patterns |
| `NettyInboundHandler.java` | convex-peer | Error handling improvement |
| `ResultConsumer.java` | convex-peer | Accept store parameter instead of `Stores.current()` |
| `BlockViewComponent.java` | convex-gui | Use `server.getStore()` directly |
| `PeerGUI.java` | convex-gui | Use `server.getStore()` directly |
| `AObserverQueue.java` | convex-observer | Remove `Stores.setCurrent()` |
| `Stores.java` | convex-core | Deprecate/remove ThreadLocal methods |
| Various test files | all | Update store setup (use explicit stores) |

## Key Design Decisions

### Why Not Just Fix the ThreadLocal?

ThreadLocal is fundamentally wrong for this use case:
- Virtual threads (Java 21+) have ThreadLocal inheritance issues
- CompletableFuture continuations run on arbitrary threads
- Netty I/O threads are shared across connections
- The pattern requires discipline that is easily violated

### In-Memory vs Persistent Decode

Messages should be decoded **without a store** by default, producing RefDirect trees:
- `CAD3Encoder.INSTANCE.decodeMultiCell(data)` (storeless) already handles this:
  it creates a temporary `MessageStore` from the message's own child cells and resolves
  all refs into direct references. No store dependency, no GC pressure, fastest access.
- For complete messages (queries, transactions, results), this produces a full RefDirect tree.
- Only the server's `BeliefPropagator` needs the persistent `EtchStore` for delta-encoded
  beliefs that reference previously stored data (partial messages).
- **MemoryStore should be avoided in production code** -- it's for testing only and carries
  OOM risk. Client code should never need a store for decoding results.
- `Message.getPayload()` (no-arg) should decode using the storeless encoder for complete
  messages. `Message.getPayload(store)` remains for server-side partial message decoding.

### Backward Compatibility

- Wire format unchanged (CAD3, VLQ framing, message types)
- Public API signatures on `Convex` class gain optional store parameter but existing
  no-arg methods remain (using the instance's store field instead of ThreadLocal)
- `Stores.current()` can be deprecated rather than removed immediately if needed for
  third-party code

## Verification Plan

1. **Unit tests** -- run module tests: `mvn test -pl convex-core` then `mvn test -pl convex-peer`
2. **Integration test** -- multi-peer network launch and sync (the existing integration tests)
3. **Grep audit** -- verify zero remaining `Stores.current()` / `Stores.setCurrent()` calls
   in production code (test code may retain some temporarily)
4. **Timeout regression** -- any test that previously passed should not start timing out;
   if it does, the store plumbing is wrong somewhere
5. **Eclipse debugging** -- run tests in Eclipse to observe timeout behaviour in real-time

## Execution Order

Error handling first -- we need fast failure with clear errors to debug the rest.
NodeServer first -- simpler than Server, same patterns, good test bed.

### Stage A: Error Handling (prerequisite for everything else)

**A1. Fix Message.getID() / getRequestID() crash on undecoded messages**
- File: `convex-core/.../message/Message.java`
- `getID()` and `getRequestID()` throw `IllegalStateException` when payload is null
- This causes cascading failures: decode error -> try to return error with ID -> crash -> connection closed -> client timeout
- Fix: return null instead of throwing when payload is undecoded
- Also fix: `getID()` has a TODO `e.printStackTrace()` that should be proper error handling

**A2. Fix NodeServer.handleIncomingMessage decode chain**
- File: `convex-peer/.../node/NodeServer.java`
- `handleIncomingMessage()` doesn't decode payload upfront
- `processPing()` calls `message.getID()` on potentially undecoded message
- `processLatticeValue()` calls `message.getPayload()` with NO store -- will fail
- `processDataRequest()` calls `message.makeDataResponse(store)` -> `getPayload()` with no store
- Error handler at line 200 doesn't include message ID in error result
- Fix: decode payload with store at entry point (like Server does), handle errors properly

**A3. Fix Server.receiveAction error recovery chain**
- File: `convex-peer/.../peer/Server.java`
- When `m.getPayload(getStore())` throws at line 95, the catch at line 97 calls `m.getRequestID()`
  which throws again because payload is still null
- This propagates to `NettyInboundHandler` which tries `m.getID()` -- also crashes
- Connection closes, client hangs until timeout
- Fix: guard ID extraction in error paths (use null ID if unavailable)

**A4. Fix NettyInboundHandler error recovery**
- File: `convex-peer/.../net/impl/netty/NettyInboundHandler.java`
- Line 109: `m.returnResult(Result.fromException(e).withID(m.getID()))` crashes when payload null
- Also: typo "enexpected" -> "unexpected" at line 106
- Fix: guard `m.getID()` call, use null if unavailable

**A5. Fix ConvexRemote.awaitResult error swallowing**
- File: `convex-peer/.../api/ConvexRemote.java`
- Line 139-141: `BadFormatException` is caught and `e1.printStackTrace()` -- should propagate as error Result
- Fix: return `Result.error(ErrorCodes.FORMAT, ...)` instead of swallowing

### Stage B: Store Refactor (after error handling is solid)

1. Add `store` field to `Convex` / `ConvexLocal` / `ConvexRemote` (additive, nothing breaks)
2. Update `ConvexLocal` to use explicit store (simplest client, fewest cross-thread issues)
3. Update `ConvexRemote` to use explicit store (harder -- async callbacks)
4. Update `Acquiror` (depends on Convex having a store)
5. Remove ThreadLocal from `AThreadedComponent` (server threads)
6. Clean up NIO `Connection`, GUI, Observer
7. Deprecate/remove `Stores.current()`

Each step should be independently testable.
