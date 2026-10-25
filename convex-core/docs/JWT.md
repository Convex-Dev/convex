# JWT time-bound validation

`JWT.validateTimeBounds(Instant now)` checks the optional `exp` and `nbf`
constraints in [RFC 7519 sections 4.1.4–4.1.5](https://www.rfc-editor.org/rfc/rfc7519.html#section-4.1.4).
It returns `null` on success or a stable, interned `AString` failure reason:

| Constant | Value | Meaning |
|----------|-------|---------|
| `JWT.INVALID_EXP` | `invalid-exp` | Present expiry is not a finite NumericDate |
| `JWT.INVALID_NBF` | `invalid-nbf` | Present not-before is not a finite NumericDate |
| `JWT.EXPIRED` | `expired` | `now >= exp` |
| `JWT.NOT_YET_VALID` | `not-yet-valid` | `now < nbf` |

Failures have the precedence shown above: claim shapes are checked before time
comparisons. Missing bounds impose no constraint; explicit null and non-numeric
values are malformed. Two absent bounds succeed. A null `now` throws
`NullPointerException`.

NumericDate uses Unix seconds, ignoring leap seconds, and permits fractions.
Integers retain their parsed precision. Fractional/exponent JSON numbers use the
JSON parser's binary64 representation; validation compares that represented
value exactly with the instant's seconds and nanoseconds. For example, binary64
`0.1` is slightly later than exactly 100 milliseconds after the epoch. Original
decimal precision lost during parsing is not recovered. Non-finite values,
including exponent overflow to infinity, are rejected. Claims are not converted
to `long` milliseconds or `Instant`, so extreme finite bounds do not overflow.
These numeric representation limits are consistent with
[RFC 8259 section 6](https://www.rfc-editor.org/rfc/rfc8259.html#section-6).

The helper reads no clock and applies no skew allowance. It does not inspect
`iat`, require expiry, limit lifetime, or validate signatures, issuers or audiences.
Applications supply time and compose the result with their authentication policy:

```java
JWT token = JWT.parse(encodedToken);
if (token == null) return Strings.intern("malformed-token");
if (!token.verifyEdDSA(trustedKey)) return Strings.intern("invalid-signature");
AString failure = token.validateTimeBounds(clock.instant());
if (failure != null) return failure;
// Continue with the application's required claims, issuer, audience and policy.
```

Temporal success alone does not establish a verified or authorised credential.
See [RFC 8725](https://www.rfc-editor.org/rfc/rfc8725.html) for JWT authentication
and profile guidance.

The existing `validateClaims` overloads retain their historical behaviour: they
use the wall clock, check optional expiry plus configured issuer/audience, accept
`exp: null` as omitted, and ignore `nbf`. They do not implement this strict contract.
