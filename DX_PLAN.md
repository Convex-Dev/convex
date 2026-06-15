# Convex Developer Experience (DX) Plan

A review of how a new developer discovers, understands, and gets started with Convex —
and a prioritised plan to improve that journey.

Scope: this repository (`Convex-Dev/convex`) and its direct touch-points with the
documentation site (`docs.convex.world`, source in the `design` repo). It does **not**
re-review the docs site content in depth, only where in-repo onboarding hands off to it.

Last reviewed: 2026-06-15.

---

## 1. The journey we are optimising

Onboarding is not one path. We have four distinct first-time personas, and each follows
a different route through the repo:

| Persona | Goal | Primary surface today | Current state |
|---------|------|-----------------------|---------------|
| **Evaluator** | "What is Convex, should I care?" | Top-level `README.md` | **Strong** |
| **App developer** | Read/write network state from my app | `convex-java` README + docs SDK pages, REST API | **Strong** |
| **Smart-contract developer** | Write & deploy a Convex Lisp actor | (no clear in-repo path) | **Weak** |
| **Peer operator** | Run a peer / local net | `convex-cli` README, docs peer-operations | **Good** |

The biggest DX gap is the **smart-contract developer** — the persona our own README sells
hardest ("Write smart contracts in a powerful, functional Lisp") yet supports least once
they clone the repo.

---

## 2. What is already good (keep / protect)

These are genuine strengths. The plan below must not regress them.

- **Top-level `README.md`** — clear value proposition, an honest comparison table, a one-line
  install, and *multiple* install options (curl/irm, direct jar, Docker, build-from-source).
  This is above the bar for the category.
- **`convex-cli/README.md`** — task-oriented ("Use Case 1…4"), with copy-pasteable commands,
  a global-options table, env-var equivalents, and documented **exit codes**. Excellent.
- **`convex-java/README.md`** — connect → create account → query → transact → async, plus a
  thread-safety caveat and correct links into the docs SDK pages (all four resolve).
- **Install scripts** (`scripts/install.sh`, `scripts/install.ps1`) — check Java version,
  pick a writable PATH dir, warn when PATH needs editing, support `CONVEX_VERSION`/`CONVEX_HOME`.
- **Docs learning path** — `design/docs/tutorial/` has a coherent, role-based ordering
  (Quick Start → Networks → SDK → Lisp → Actors → Recipes → Peer Ops) and a real glossary.

---

## 3. Issues found (with evidence)

### P0 — Blocks or breaks first contact

**3.1 [VERIFIED — README is correct; script comments were wrong. FIXED.]**
Hypothesis was that the install one-liner 404s. Verified live (2026-06-15):
- `https://convex.world/install.sh` → **200, valid script** ✓ (the URL the README uses)
- `https://convex.world/install.ps1` → **200, valid script** ✓
- `https://convex.world/public/install.sh` → **404** ✗ (the path the *scripts'* SYNC
  comments cited)

So the README never broke. The actual defect was the misleading SYNC comment in
`scripts/install.sh` and `scripts/install.ps1`, which pointed at a 404 URL. **Fixed** —
both comments now cite the verified live `https://convex.world/install.{sh,ps1}`.

**3.2 "Java Examples" link was misleading. [FIXED.]**
`convex-core/README.md` linked "Java Examples" to `convex-core/src/test/java/examples`,
which contains only `generators/BadListGen.java` and `BadListTest.java` — test-data
generators, not illustrative examples. **Fixed** — repointed to
`src/test/java/convex/core/examples` (8 files, incl. the well-commented `RawCVM.java`
"run CVM code directly" example, plus the CPoS/belief simulations).
(The adjacent **Convex Lisp examples** link to `src/test/resources/examples` was already good —
`adventure.cvx`, `token-demo.cvx`, `language.cvx`, `smart-contract-shop-demo.cvx`, etc.)

### P1 — High-friction, high-impact

**3.3 Three different "front doors", no single golden path.**
- `README.md` Getting Started → `convex desktop` / `convex local gui` (GUI first).
- `convex-cli/README.md` → `convex local start` (CLI first).
- `docs … /tutorial/quickstart` → "first transaction in 5 minutes" on a hosted testnet.

A newcomer cannot tell which is *the* way to start, and the README's chosen first action
(open the GUI) does not deliver a concrete win — there is no "and now run *this* to see it
work". We need one canonical, copy-pasteable "first 5 minutes" that ends in a visible result,
with the other surfaces pointing back to it.

**3.4 README never links to the 5-minute quickstart.**
The headline onboarding section hands off to the GUI but does not say "now follow
docs.convex.world/docs/tutorial/quickstart". The best getting-started asset we have is
effectively undiscoverable from the repo front page.

**3.5 Convex Lisp / first-actor has no in-repo path.**
Nothing in the README or a top-level tutorial shows a "hello world" actor or a single
Convex Lisp expression. The `.cvx` examples are buried in `convex-core/src/test/resources/`
and referenced only from a deep module README. For the persona we market to most, the repo
offers no on-ramp.

**3.6 The REPL — our best learning tool — is hidden.**
`convex local gui` and the docs `tools/convex-repl` exist, but the README never says "open
the REPL and type `(+ 1 2 3)`". Interactive REPL exploration is the fastest way to learn
Convex Lisp and we don't surface it.

**3.7 Prerequisite points at Oracle JDK.**
`README.md:78` links Java downloads to oracle.com (login wall, licensing friction). Adoptium
Temurin is the friendlier, no-account default and matches what the install scripts accept.

### P2 — Structural / drift risks

**3.8 Hand-maintained CLI command reference drifts.**
`convex-cli/README.md` has a hand-written command tree and options tables. These will diverge
from the actual picocli command set over time. There is an asciidoc docs source under
`convex-cli/src/docs/` — we should generate the reference (or at least test it) rather than
maintain it by hand.

**3.9 Hardcoded version strings across READMEs.**
`0.8.5` appears literally in `convex-core`, `convex-java`, `convex-restapi` install snippets.
BUILD.md documents a release-time `sed` sweep, so it is *managed* — but it is still a
drift/forget risk and a poor signal if one slips.

**3.10 No "first contribution" on-ramp.**
`BUILD.md` is release-focused; the README "Contributing" section is about licensing/CLA. There
is no short "clone → build → run tests → where the interesting code is" guide for a developer
who wants to *change* Convex, not just use it.

**3.11 Examples are fragmented.**
`.cvx` samples live in test resources; Java "examples" are generator fixtures; the GUI ships
demos; the docs have recipes. There is no single discoverable `examples/` story.

### P3 — Polish / longer-term

**3.12 No signed/notarized native app.**
macOS/Windows users who download the jar get OS security prompts (the trigger for this
review was exactly such a prompt on macOS). `BUILD.md` mentions `jpackage` but we ship no
notarized `.dmg`/signed installer. For non-CLI users this is the first impression.

**3.13 Docs are unversioned relative to the jar.**
A user on an older jar reads HEAD docs. Not urgent, but worth noting for when the API moves.

---

## 4. Plan

Ordered by impact-per-effort. Each item is independently shippable.

### Phase 0 — Stop the bleeding (hours) — DONE 2026-06-15

- [x] **Verify the install URLs resolve** (3.1). Verified live: README's `/install.sh` and
      `/install.ps1` both 200; the scripts' `/public/...` SYNC comments 404'd. Corrected both
      script comments to cite the live URL.
- [x] **Fix the "Java Examples" link** (3.2). Repointed `convex-core/README.md` to
      `src/test/java/convex/core/examples` (incl. `RawCVM.java`).
- [x] **Switch the Java prerequisite link to Adoptium Temurin** (3.7). `README.md` now links
      Temurin 21 instead of the Oracle JDK download (no login wall).

### Phase 1 — One golden path (days)

- [ ] **Define the canonical "first 5 minutes"** (3.3, 3.4). Pick one front door — recommendation:
      *hosted testnet + REPL/CLI*, because it needs no local peer and produces a visible result
      fastest. Write it once (ideally in the docs `quickstart`), then have the README,
      `convex-cli` README, and GUI README all link to that one page instead of inventing their own.
- [ ] **Add a "Your first transaction" block to the README** that ends in an observable result,
      then links to the full quickstart. Make the GUI a *second* step, not the first.
- [ ] **Surface the REPL** (3.6). One line in the README: open the REPL, type `(+ 1 2 3)`,
      then a `(deploy …)` one-liner.
- [ ] **Add an in-repo "Hello, actor" snippet** (3.5) — a minimal `^:callable` actor a reader
      can paste into the REPL or `convex transact`, with a pointer to the `.cvx` examples and the
      docs Lisp guide.

### Phase 2 — Make it durable (weeks)

- [ ] **Generate the CLI command reference** from picocli (or assert it in a test) (3.8).
- [ ] **Add a `CONTRIBUTING.md` quickstart** (3.10): clone → `mvn install` → `mvn test` →
      module map → "good first issue" pointer. Keep BUILD.md for release mechanics.
- [ ] **Consolidate examples** (3.11): a top-level `examples/` (or a single docs page) that
      indexes the `.cvx` samples, the SDK snippets, and the GUI demos. One place to point people.
- [ ] **Version-string hygiene** (3.9): consider a docs build-time token, or a CI check that the
      README versions match the released `pom.xml` version, so a missed bump fails loudly.

### Phase 3 — Reduce first-impression friction (later)

- [ ] **Notarized macOS `.dmg` / signed Windows installer** via `jpackage` in the release
      workflow (3.12), so GUI users do not hit OS security warnings.
- [ ] **Consider doc versioning** once the public API surface starts moving (3.13).

---

## 5. How we will know it worked

DX is hard to measure, but these are observable proxies:

- **Time-to-first-result**: a new developer goes from the README to a visible transaction/query
  result with zero detours. Target: < 5 minutes, one page, no dead links.
- **Zero broken links** from any in-repo README into the docs or the filesystem (add a
  link-check to CI).
- **Smart-contract on-ramp exists**: a reader can deploy a trivial actor without leaving the
  repo's recommended path.
- **Single front door**: every "getting started" surface routes to the same canonical quickstart.

---

## 6. Open questions for the team

1. ~~Which front door is canonical?~~ **DECIDED (§7.3)**: the Web REPL is the headline quick start
   on every surface; SDK / own-peer / Lisp are secondary options below it. Testnet for onboarding.
2. ~~Does the web server rewrite `/install.sh`, or is `/public/` the real path?~~ **Resolved**:
   `/install.sh` and `/install.ps1` are served directly (200); `/public/...` 404s. Scripts fixed.
3. Is a notarized native build in scope for the next release, or deferred? (3.12.)
4. Should examples live in-repo (`examples/`) or only in the docs `recipes` section? (3.11.)

---

## 7. Cross-repo findings — convex.world (website) and design (docs)

The onboarding journey spans three repos. Reviewed 2026-06-15. Same P0–P3 lensing.
None of these are fixed yet — they live in other repos (and `convex.world` currently has
uncommitted WIP, so I left it untouched).

### 7.1 convex.world (Next.js marketing site)

**Strengths:** a *real* live Sandbox/REPL against an actual peer (not just marketing); a strong
Developers dropdown deep-linking SDKs/CLI/Desktop/CADs; and a Downloads page whose install
one-liner matches the convex repo exactly.

**Issues:**
- **[P0] Broken jar download on the Tools page.** `curl -O https://convex.world/convex.jar`
  resolves to an HTML meta-refresh stub (`public/convex.jar/index.html`), not a jar — `curl -O`
  saves the HTML. Contradicts the correct install.sh/releases path on the Downloads page.
  (`src/app/(developers)/tools/page.tsx`)
- **[P1] No developer CTA above the fold.** The hero offers only "Vision" and "Sandbox" — no
  Get Started / Docs / Install / GitHub. (`src/components/Hero.tsx`)
- **[P1] Stale "Protocol v0.7.14"** hardcoded in the sitewide footer (actual 0.8.x).
  (`src/components/Footer.tsx`)
- **[P2] Testnet vs Protonet mixed signals.** Default interactive peer is the personal
  `mikera1337-…hf.space` testnet, while comments/footer/nav say `peer.convex.live`.
  (`src/lib/convex-api.ts`, `Footer.tsx`)
- **[P2] Local-dev README inaccurate** — wrong page path (`app/` vs `src/app/`) and silent on the
  sibling `convex.ts` build prerequisite that actually blocks `pnpm install`; accurate steps are
  only in `AGENTS.md`. (`README.md`)
- **[P3] Discord invite mismatch** — site uses `xfYGq4CT7v`; the convex repo README uses
  `discord.gg/5j2mPsk`. Reconcile to one.

**Fixed 2026-06-15:** broken Tools-page jar download, stale footer version (→ v0.8.5), README
`app/`→`src/app/` path, Discord invite (→ canonical). Hero CTA + testnet/Protonet default now
resolved by §7.3 (implementation pending). The `convex.ts` "build prerequisite" was a non-issue —
the dependency is npm `^0.3.0`, not a `file:` ref, so `pnpm install` works without it.

### 7.2 design (Docusaurus docs, docs.convex.world)

**Strengths:** clear role-based learning paths; a genuinely beginner-friendly "Gentle Lisp
Introduction"; and a self-contained Java local-peer quickstart that produces real output (the
single strongest onboarding artefact). Three of four SDKs have full, parallel page sets.

**Issues:**
- **[P0] The flagship "zero-install testnet, 1 min" quickstart can't run end-to-end.** Account
  creation / funding is commented out (`quickstart.md:134,155`), so the promised first win never
  lands — a copy-paste newcomer hits a no-account/FUNDS wall. (`tutorial/quickstart.md`)
- **[P1] No GUI / web-REPL front door in the quickstart.** The actual fastest path (Desktop
  terminal or web sandbox) is omitted; the sandbox setup in the Lisp guide is commented out.
  (`quickstart.md`, `convex-lisp/lisp-guide.md:22-30`)
- **[P1] Convex Lisp landing page dead-ends.** `convex-lisp/index.md` never links to its own
  "Gentle Lisp Introduction" or child pages (only external clojure/racket links). Add a "start
  here" block like `actors/index.md` has. (`convex-lisp/index.md`)
- **[P1] Four broken relative links** in `actors/index.md` — `./actors/concepts` etc. resolve to
  `tutorial/actors/actors/…` (should be `./concepts`). A `pnpm build` link-check would catch
  these. (`actors/index.md:14-17`)
- **[P1] Stale `convex-java:0.8.2`** pinned across quickstart/index/java pages (released is 0.8.5);
  peer-ops download URLs hardcode `v0.8.2`/`v0.8.3`.
- **[P2] JavaScript SDK structural parity gap** — a single page, sidebar `items: []`, silently
  redirecting to the TypeScript sub-pages, while badged "production ready" like the full SDKs.
  (`client-sdks/javascript/index.md`, `sidebars.ts`)
- **[P2] "Production" mislabeled with the testnet URL** in `client-sdks/java/quickstart.md:21`.
- **[P2] README omits local docs build** (pnpm) — only in `AGENTS.md`.

**Fixed 2026-06-15:** four broken `actors/` links, Convex-Lisp landing-page dead-end ("Where to
start" block added), `convex-java` versions → 0.8.5, peer-ops download URLs (dropped bad `v`
prefix), "Production" URL → `peer.convex.live`, docs README local-build section. Still open: the
non-runnable testnet quickstart (P0, pending §7.3 impl) and JS SDK parity (P2, pending decision).

### 7.3 The golden path — DECIDED (REPL headline, then options)

Resolved 2026-06-15 (mike). The README, docs quickstart, and website hero all open the same way:

**Headline quick start — the Web REPL (Sandbox) on convex.world.** Zero-install, universal first
win: paste `(+ 1 2 3)`, see a result, then deploy a one-line actor. This is the hook everyone hits
first — no choice required up front.

**Then secondary "go further" options** below the headline (cards/links):
- **Try an SDK** — TS / Java / Python against the hosted testnet (faucet account) → build an app.
- **Run your own peer** — `convex.jar` via CLI (`convex local start`) or Convex Desktop → full control / offline.
- **Write Convex Lisp** — language guide + actor development.
- **Operate a peer** — peer-operations.

Testnet endpoint stays `mikera1337-convex-testnet.hf.space` for now (a **proper branded testnet is
planned**); keep the value in one place per surface so the eventual swap is a one-line change, not
another scattered find-replace. This also settles testnet-vs-Protonet: **testnet for onboarding**,
Protonet referenced as production.

This decision drives the still-open implementation of 3.3–3.6 (README first-5-min + REPL + first
actor), the 7.2 quickstart restructure, and the 7.1 hero CTA (hero leads with the REPL "try it"
plus the secondary options + a Docs/GitHub link).

### 7.4 Recurring cross-repo theme: version drift

Stale version/identity strings appear in **all three** repos (convex READMEs `0.8.5` by hand;
docs `0.8.2`; website footer `v0.7.14`; two different Discord invites). This argues for a single
source of truth plus a CI check that fails when published surfaces disagree with the released
`pom.xml` version (ties to item 3.9).
