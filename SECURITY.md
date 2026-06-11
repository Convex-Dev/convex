# Security Policy

Convex is decentralised infrastructure that hosts value-bearing accounts and assets.
We take security seriously and welcome responsible disclosure of vulnerabilities.

## Reporting a Vulnerability

**Please do not report security vulnerabilities through public GitHub issues, pull
requests, or the Discord server.**

Instead, use one of the private channels below:

- **GitHub private vulnerability reporting** (preferred): go to the
  [Security tab](https://github.com/Convex-Dev/convex/security) of this repository and
  choose **Report a vulnerability**. This opens a private advisory visible only to the
  maintainers.
- **Email**: `info@convex.world`. Use the subject line `SECURITY` and, if possible,
  encrypt sensitive details.

Please include as much of the following as you can:

- A description of the vulnerability and its impact.
- Affected module(s) and version (`mvn -version`, release tag, or commit hash).
- Step-by-step reproduction instructions or a proof-of-concept.
- Any suggested mitigation, if you have one.

## What to Expect

- We aim to acknowledge new reports within **5 working days**.
- We will keep you informed of progress as we investigate and prepare a fix.
- We will coordinate a disclosure timeline with you and credit you in the release
  notes and advisory (unless you prefer to remain anonymous).

Please give us a reasonable opportunity to release a fix before any public disclosure.

## Scope

Areas of particular interest:

- **Consensus / CPoS** — anything that could break safety or liveness, or allow a peer
  to forge or replay Beliefs.
- **CVM execution** — sandbox escapes, incorrect Juice accounting, or state corruption.
- **Peer networking & REST API** — these surfaces are **public by default** and must be
  robust against malicious messages (malformed encodings, resource exhaustion, etc.).
- **Cryptography** — key handling, Ed25519 signature validation, Etch data integrity.

Lattice nodes are **private by default** — operators choose who they share and merge
with — so issues that require an operator to trust a malicious peer are lower severity,
though still worth reporting.

## Supported Versions

Security fixes are applied to the latest release and the `develop` branch. We generally
do not backport fixes to older releases; please upgrade to the latest version.

| Version            | Supported |
|--------------------|-----------|
| Latest release     | ✅        |
| `develop` (snapshot) | ✅      |
| Older releases     | ❌        |
