# Convex Developer Experience (DX) Plan

How a new developer discovers, understands, and gets started with Convex — and what is left
to improve. Covers the three onboarding repos: `convex`, `design` (docs.convex.world), and
`convex.world` (website).

Reviewed 2026-06-15 · last updated 2026-06-15.

## Status

A full onboarding review is done and **almost all quick wins are shipped** (committed and
pushed across the three repos — see §3). The major design decision — the **golden path** — is
settled (§1). The one substantial piece of work still outstanding is **implementing** that
golden path across the README, docs quickstart, and website hero (§2). A handful of items are
deliberately deferred (§4).

---

## 1. The golden path (decided — drives the remaining work)

The README, docs quickstart, and website hero should all open the same way:

**Headline quick start — the Web REPL (Sandbox) on convex.world.** Zero-install, universal
first win: paste `(+ 1 2 3)`, see a result, then deploy a one-line actor. No persona choice
required up front.

**Then secondary "go further" options** below the headline (cards/links):
- **Try an SDK** — TypeScript/JavaScript, Java, or Python against the hosted testnet (faucet
  account) → build an app.
- **Run your own peer** — `convex.jar` via CLI (`convex local start`) or Convex Desktop →
  full control / offline.
- **Write Convex Lisp** — language guide + actor development.
- **Operate a peer** — peer-operations.

**Network:** testnet for onboarding, Protonet referenced as production. The testnet endpoint
stays `mikera1337-convex-testnet.hf.space` for now (a proper branded testnet is planned) — keep
the value in **one place per surface** so the eventual swap is a one-line change.

This maps cleanly to the four personas: evaluators and smart-contract-curious devs land on the
REPL; app developers branch to an SDK or their own jar; operators go to peer-ops.

---

## 2. Remaining work — implement the golden path

One substantive task, best done **docs-first** (the quickstart is the canonical source the
other surfaces link to), with a `pnpm build` check before each push.

- [ ] **Docs `tutorial/quickstart.md`** — restructure to REPL-headline + options. Fix the
      flagship "zero-install testnet" path, which currently **cannot run end-to-end**: account
      creation/funding is commented out (`quickstart.md:134,155`), so a copy-paste newcomer hits
      a no-account/FUNDS wall. Add the genuinely-fastest path (web Sandbox or Desktop Client
      Terminal) — today the quickstart omits it and the Lisp-guide sandbox block is commented
      out (`convex-lisp/lisp-guide.md:22-30`).
- [ ] **convex `README.md`** — add a "Your first transaction" block that leads with the REPL and
      ends in an observable result, plus a minimal `^:callable` "hello, actor" snippet; link to
      the docs quickstart. Make the GUI a *second* step, not the first.
- [ ] **convex.world hero** (`src/components/Hero.tsx`) — add a primary "Start building" CTA that
      routes to the golden path (REPL try-it + the secondary options + a Docs/GitHub link). The
      hero currently offers only "Vision" and "Sandbox".

---

## 3. Completed 2026-06-15 (committed + pushed)

**convex**
- Install-script SYNC comments corrected to the live `convex.world/install.{sh,ps1}` URLs
  (verified: `/install.sh` 200, `/public/...` 404).
- `convex-core` README "Java Examples" link → real examples (`convex/core/examples`, incl. `RawCVM.java`).
- README Java prerequisite → Adoptium Temurin (no Oracle login wall).
- README Discord link → canonical invite (`discord.com/invite/xfYGq4CT7v`).
- README "Examples" section indexing the `.cvx` demos, the Java examples, and the docs recipes/SDK quickstarts.
- Desktop (`convex-gui`) README: "First run on macOS / Windows" note (Gatekeeper/SmartScreen workaround for the unsigned jar).
- `BUILD.md`: release checklist now lists downstream version references (docs + website footer) to bump in lockstep.

**design (docs)**
- `convex-java` version `0.8.2` → `0.8.5` across quickstart/index/SDK pages.
- Peer-ops download URLs fixed (dropped the bad `v` prefix; tags have none) and bumped to `0.8.5`.
- `java/quickstart` "Production" now points at `peer.convex.live` (was the testnet URL).
- Convex-Lisp landing page: "Where to start" block linking the Lisp guides (slug-safe `.md` links).
- docs README: "Running the docs locally" (pnpm) section.
- **JS → TS SDK fold**: one "TypeScript / JavaScript" SDK; the (substantial) JS guide re-parented
  under it, separate top-level JS category removed; URL unchanged so inbound links still resolve.
- Verified clean with `pnpm build`.

**convex.world**
- Tools-page jar download now pulls the real release asset (was an HTML-stub `curl -O`).
- README editable-page path `app/` → `src/app/`.
- Footer: stale `v0.7.14` → `v0.8.5`, HTTPS swagger URL, internal links via `next/link`.
- Downloads page: jar first-run OS-prompt note.
- Installer hardening (atomic download, robust PATH/writability checks) + a CI workflow that
  lint/typecheck/test/builds and smoke-tests the installers on Linux & Windows.

---

## 4. Deferred / parked (with rationale)

- **CLI reference generation** — the `convex-cli` README command table is hand-maintained and will
  drift; generate it from picocli *if/when* that becomes a maintenance pain.
- **`CONTRIBUTING.md` quickstart** — a short "clone → `mvn install` → `mvn test` → module map →
  good-first-issue" guide would help code contributors. New surface; draft on request.
- **Docs versioning** — users on an old jar read HEAD docs. Revisit when the public API moves.
- **Notarized native installers** — decided **no**: the audience can run a jar, so we *documented*
  the OS first-run prompt instead. Revisit signing/notarisation only if Desktop becomes a featured
  distribution (and gate any notarisation to release branches — it has been slow/expensive before).
- **Version-drift CI guard** — the release checklist covers it now; a grep-based CI check that
  fails when published surfaces disagree with the released `pom.xml` version is an optional add-on.
- **Pre-existing broken docs links** (found via `pnpm build`, out of scope): `overview/* →
  cad/*/README.md` in `faq.md`, `lattice.md`, `use-cases.md`. Good first targets for a docs
  link-check in CI.

---

## 5. Strengths to protect (don't regress)

- **`README.md`** — clear value prop, honest comparison table, one-line install + multiple options.
- **`convex-cli/README.md`** — task-oriented use-cases, copy-pasteable commands, documented exit codes.
- **`convex-java/README.md`** — connect → account → query → transact → async, with a thread-safety caveat.
- **Install scripts** — Java-version check, writable-PATH handling, `CONVEX_VERSION`/`CONVEX_HOME`.
- **Docs** — coherent role-based learning path, a genuinely beginner-friendly "Gentle Lisp
  Introduction", and a self-contained Java local-peer quickstart that produces real output.
- **convex.world** — a real live Sandbox/REPL against an actual peer, and a strong Developers menu.

## 6. Success measures

- **Time-to-first-result** < 5 minutes, one page, no dead links.
- **Zero broken links** from any README/doc (add a CI link-check).
- **Smart-contract on-ramp exists** — a reader can deploy a trivial actor without leaving the path.
- **Single front door** — every "getting started" surface routes to the same golden path.

---

*Lesson logged: verify doc links with `pnpm build`, not by reasoning about paths — `design` uses
`trailingSlash: false` (relative links resolve against the parent dir), custom page `slug`s, and
`onBrokenLinks: 'warn'` (broken links don't fail the build).*
