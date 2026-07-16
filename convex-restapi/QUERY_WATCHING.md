# Query-result watching

`GET /api/v1/watch` opens a Server-Sent Events stream for one CVM query. The
query is evaluated immediately against current consensus state and again whenever
finalised state advances. A new event is emitted only when the complete `Result`
hash changes.

The endpoint is disabled by default because every connected query consumes
bounded execution resources whenever finalised state advances. Enable it in the
JSON5 REST configuration:

```json5
{
  "rest": {
    "queryWatch": true
  }
}
```

Code using the legacy flat configuration may instead set
`Keywords.QUERY_WATCH` to `true`. When disabled, the route is not registered and
returns HTTP 404. Filtered `/api/v1/watch/logs` delivery remains enabled.

The request must include `Accept: text/event-stream` and exactly one `source`
parameter. Using `curl --data-urlencode` avoids manual query-string escaping:

```bash
curl -N -G -H "Accept: text/event-stream" \
  --data-urlencode 'source=*timestamp*' \
  --data 'format=json' \
  http://localhost:8080/api/v1/watch
```

## Parameters

| Parameter | Required | Meaning |
|-----------|----------|---------|
| `source` | Yes | CVX source for exactly one query form |
| `address` | No | Account context as `123`, `%23123`, or `0x7b`; defaults to the genesis account |
| `format` | No | Event data encoding: `json` (default) or `cvx` |

The query has normal detached-query semantics: its state changes are not retained.
Successful and exceptional CVM results are both delivered as `result` events.

## Event shape

Every event is named `result`. Its SSE ID is the finalised state position at
which the query was evaluated. Positions can skip when intervening states produce
the same result.

JSON output follows the existing REST representation of `Result`, nested with its
state position:

```text
id: 1042
event: result
data: {"position":1042,"result":{"value":6,"result":"6","info":{"source":"PEER"}}}
```

CVX output preserves the native `Result` and all CVM types:

```text
id: 1042
event: result
data: {:position 1042 :result #Result {:result 6,:info {:source :PEER}}}
```

Map field order is not significant. Query logs, errors, and execution information
are retained inside the nested `Result` using the normal result representation.

## Delivery and limits

- There is no polling. A shared asynchronous distributor coalesces finalised Peer
  updates and performs query execution away from the consensus thread.
- The observer and distributor exist only while at least one query stream is
  connected.
- Each query evaluation is capped at 100,000 Juice. Hitting the cap produces a
  normal `:JUICE` result, which is delivered to the client.
- At most 100 query streams may be connected. This bounds aggregate query work to
  at most the normal 10,000,000-Juice transaction maximum per evaluation round.
- Query source is limited to 4,096 characters. Encoded events are limited to 64K
  characters and each connection has a bounded 16-event queue.
- A slow client or oversized result closes only that stream. It cannot block
  consensus processing or delivery to other clients.
- Clients should reconnect after disconnection and treat the first result as the
  new baseline. `Last-Event-ID` is useful for detecting a gap but does not request
  replay; historical replay is outside this endpoint's contract.
