# Convex x402

Support for the [x402 payment protocol](https://www.x402.org/) (v2) on Convex,
as specified in CAD042. x402 enables HTTP-native payments using the
`402 Payment Required` status code, with Convex as the settlement network.

This module is transport-agnostic and dependency-light. It provides:

- `convex.x402` — protocol constants, header codecs, CAIP-2 network identity
- `convex.x402.model` — the x402 v2 JSON model (`PaymentRequired`,
  `PaymentRequirements`, `PaymentPayload`, `VerifyResponse`,
  `SettlementResponse`)
- `convex.x402.scheme` — the Convex `exact` scheme binding: payments are
  pre-signed Convex transactions verified structurally against a canonical
  form, then against consensus state
- `convex.x402.facilitator` — facilitator core (verify/settle/supported)
  working against any `convex.api.Convex` connection
- `convex.x402.client` — an HTTP client wrapper that pays x402 demands with
  locally-signed Convex transactions

The HTTP endpoints (`/x402/verify`, `/x402/settle`, `/x402/supported`) and the
payment gate for protecting routes are provided by `convex-restapi`.

See `docs/proposals/X402.md` in the repository root for the design, and CAD042
for the protocol binding.

## License

Copyright 2026 The Convex Foundation

Licensed under the Apache License, Version 2.0
