# Filtered log watching

`GET /api/v1/watch/logs` opens a Server-Sent Events stream of finalised CVM log
entries. It watches new finalised results from the point of subscription; it is
not a historical query or a resumable replay API.

The request must include `Accept: text/event-stream` and at least one `address`
filter. Requiring an emitting account prevents an accidental subscription to
every log on the network.

```bash
curl -N -H "Accept: text/event-stream" \
  "http://localhost:8080/api/v1/watch/logs?address=123&event=%3ATRANSFER&scope=%3AUSD&format=json"
```

## Filters

| Parameter | Required | Meaning |
|-----------|----------|---------|
| `address` | Yes | Exact emitting account address, as `123`, `%23123`, or `0x7b` |
| `event` | No | Exact CVX value in the first position of the logged values vector |
| `scope` | No | Exact CVX value in the CAD27 scope field |
| `format` | No | Event data encoding: `json` (default) or `cvx` |

Each filter may be supplied up to 16 times. Repeated values within one dimension
are alternatives (OR); the supplied dimensions must all match (AND). `event` and
`scope` values are parsed as CVX, so URL encoding is normally required. For
example, `scope=nil` selects only unscoped entries, while omitting `scope` accepts
all scopes.

Scope is useful when one Actor account represents several assets or other logical
instances. For example, `address=123&scope=%3AUSD` watches only the `:USD` instance
within Actor `#123`. Adding `event=%3ATRANSFER` further selects its transfer event.

## Event shape

Every match is an SSE event named `log`. Its ID is
`block-index:transaction-index:log-index`.

JSON output:

```text
id: 1042:3:0
event: log
data: {"block":1042,"transaction":3,"log":0,"entry":[123,"USD",[1042,3],["TRANSFER",100]]}
```

CVX output preserves native CVM types:

```text
id: 1042:3:0
event: log
data: {:block 1042 :transaction 3 :log 0 :entry [#123 :USD [1042 3] [:TRANSFER 100]]}
```

Map field order is not significant. `entry` is the canonical CAD27 vector
`[address scope location values]`; the envelope supplies explicit consensus block,
transaction, and log indexes.

## Delivery and limits

- The consensus scanner and state observer exist only while at least one log
  stream is connected. All subscribers share one scanner.
- Filtering happens before JSON or CVX encoding. A matching event is encoded at
  most once per output format, regardless of subscriber count.
- Network writes run independently of consensus updates. Each connection has a
  bounded 16-event queue; a slow client that fills it is disconnected without
  delaying other clients or the Peer.
- The endpoint permits at most 100 concurrent connections and at most 64K characters of
  encoded data per event. Oversized or persistently slow streams are closed.
- Clients should reconnect and re-register filters after a disconnect. Because
  there is no replay, clients that require recovery should query application state
  after reconnecting.

The scanner currently exposes logs retained in ordinary finalised transaction
results. Logs produced while executing scheduled transactions are not retained by
the current state execution path and therefore cannot be emitted here.
