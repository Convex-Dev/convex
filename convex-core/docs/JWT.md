# JWT

`convex.auth.jwt.JWT` signs and verifies compact JSON Web Tokens
([RFC 7519](https://www.rfc-editor.org/rfc/rfc7519.html)) with EdDSA (Ed25519)
and HS256, and verifies RS256 tokens from external OAuth providers.

## Signing

A signer writes the caller's JOSE header and claims. It controls only `alg`,
which follows from the key: `EdDSA` for an `AKeyPair`, `HS256` for a shared
secret. A header without `alg` gains it; a header naming another algorithm is
refused with `IllegalArgumentException`. Nothing else is added, so each token
profile gets exactly the header it requires:

```java
// DIF Well-Known DID Configuration: alg and kid only, no typ
JWT.signPublic(Maps.of(JWT.KID, verificationMethod), claims, keyPair);
// Explicit typing (RFC 8725 section 3.11), e.g. an RFC 9068 access token
JWT.signPublic(Maps.of(JWT.TYP, "at+jwt", JWT.KID, kid), claims, keyPair);
JWT.signSymmetric(Maps.of(JWT.TYP, "at+jwt"), claims, secret);
```

The signers without a header use `typ: JWT`, as UCAN 0.10 requires:

| Signer | Header besides `alg` |
|--------|----------------------|
| `signPublic(claims, keyPair)` | `typ: JWT`, `kid`: the key's multikey |
| `signPublic(claims, keyPair, kid)` | `typ: JWT`, `kid` as given |
| `signSymmetric(claims, secret)` | `typ: JWT` |

A `kid` is copied unchanged, so it may be a DID URL naming a verification
method; the signer does not check that it names the signing key.

## Verifying

Each static verifier parses the token, checks `alg` and the signature, and
returns the claims, or null if any step fails:

| Verifier | Key |
|----------|-----|
| `verifyPublic(jwt, key)` | A trusted Ed25519 key |
| `verifyPublic(jwt)` | The Ed25519 key in `kid`: a bare multikey, `did:key`, or a DID URL with a multikey fragment |
| `verifySymmetric(jwt, secret)` | A shared secret |
| `verifyRS256(jwt, key)` | An RSA public key |

`verifyPublic(jwt)` trusts the sender's `kid`, so it proves only that the token
was signed by the key it names; bind any identity claim to that key before
trusting it. `JWT.parse` returns an instance whose `verifyEdDSA`, `verifyHS256`
and `verifyRS256` make the same checks and return a boolean.

Verification does not read `typ`: a profile that requires one checks it, as
`UCAN` does for `JWT`.

## Time bounds

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

The `validateClaims` overloads are looser: they use the wall clock, check optional expiry plus configured issuer/audience, accept
`exp: null` as omitted, and ignore `nbf`. They do not implement this strict contract.
