/**
 * User Controlled Authorisation Networks (UCAN) — capability-based
 * authorisation tokens for delegated access.
 *
 * <p>UCANs extend JWTs with cryptographically verifiable capability chains,
 * allowing fine-grained, offline-verifiable delegation between Convex accounts
 * and other agents. This package provides token construction, capability
 * modelling, and two-layer validation: chain integrity, then per-request
 * authority with pluggable root-authority policies
 * ({@link convex.auth.ucan.RootAuthorityPolicy}).</p>
 *
 * <p>See: <a href="https://ucan.xyz/">ucan.xyz</a></p>
 */
package convex.auth.ucan;
