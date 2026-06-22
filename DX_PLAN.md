# Convex Developer Experience (DX) Plan

How a new developer discovers, understands, and gets started with Convex — and what is left
to improve. Covers the three onboarding repos: `convex`, `design` (docs.convex.world), and
`convex.world` (website).

Reviewed 2026-06-15 · **golden path implemented 2026-06-19** · last updated 2026-06-19.

## Status

The onboarding review, the quick wins, **and the golden path itself are now implemented**. The
design decision (§1) is settled and built (§2, committed 2026-06-19): the docs quickstart,
convex `README`, and convex.world hero all open with the zero-install Web Sandbox, and the
previously-broken zero-install path now runs end-to-end. Along the way the Python SDK was
renamed (`convex-sdk` / `import convex_sdk`) and re-released so its docs are accurate, and a
naming sweep brought the docs in line (§4).

What's left is **deploying** the docs/site changes and a short list of **verification
follow-ups** — see **§3 Next steps**. A handful of items remain deliberately deferred (§5).

---

## 1. The golden path (decided — now implemented)

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

## 2. Implement the golden path — DONE (2026-06-19)

Built docs-first (the quickstart is the canonical source the other surfaces link to), verified
with `pnpm build` and a live testnet check of the headline snippet. Committed across the three
repos; **deploy pending** (§3).

- [x] **Docs `tutorial/quickstart.md`** — restructured to the Web-Sandbox headline (evaluate →
      faucet-funded account → deploy + call a `^:callable` actor) with "Go further" cards (Try an
      SDK with Java/Python/JS-TS buttons, run a peer, Convex Lisp, operate a peer). The broken
      zero-install path is fixed: all three SDK examples now create + fund + wire an account
      before transacting, and the actor deploy/call is verified live on the testnet. Stale
      `(export …)` actor examples were updated to `^:callable`.
- [x] **convex `README.md`** — added a "Your first transaction (no install)" block leading with
      the Sandbox + a `^:callable` "hello, actor", linking to the docs quickstart; install/GUI is
      now the step *after*.
- [x] **convex.world hero** (`src/components/Hero.tsx`) — added a primary "Start building" CTA →
      `/developers`; kept Sandbox; demoted Vision to secondary.

The **Lisp-guide sandbox block** (`convex-lisp/lisp-guide.md`) has since been uncommented and now
leads the guide, which was also corrected (see §4).

---

## 3. Next steps

**Ship it — DONE (2026-06-22): all deployed and verified live.**
- `design` → **docs.convex.world** live (golden-path quickstart, fixed SDK quickstarts, corrected
  Lisp guide, naming sweep, link-check).
- `convex.world` → **live** as a full `develop`→`master` release (the hero + ~10 accumulated team
  commits). Fixed a `pnpm/action-setup` version-vs-`packageManager` conflict that had blocked
  every convex.world deploy since March.

**Verify (before/after shipping):**
- [x] **Per-language SDK quickstarts** — verified and fixed (2026-06-19). Each was broken or
  inconsistent: the **Python** page connected to production then called the faucet (testnet-only)
  → pointed at the testnet; the **Java** page used the binary `convex.api.Convex` client against
  an HTTPS endpoint with fake `#1234` addresses → rewritten to `ConvexJSON` + `useNewAccount`;
  the **TypeScript** page required a BYO production account → now leads with a testnet
  create+faucet flow (production/BYO kept as a note). Python verified end-to-end on the published
  package; Java live spot-checked on the testnet.
- [x] **Java testnet example** — live spot-checked (`ConvexJSON` + `useNewAccount` → transact →
  query → `JAVA_OK`); this also confirms the top-level quickstart's Java example.
- [x] **Lisp-guide sandbox block** — uncommented (now leads the guide); also fixed several wrong
  outputs (verified live), the quote/unquote → quasi-quote error, an unclosed paren and typos,
  localised the screenshots, and added clojure syntax highlighting.

**Keep one-line-swap items from scattering again:**
- The **testnet endpoint** `mikera1337-convex-testnet.hf.space` is still a placeholder for a
  proper branded testnet. It now appears in the quickstart, README, and SDK examples — keep each
  surface's value in one place so the eventual swap stays cheap.

**CI hygiene:**
- [x] **Docs link-check — DONE (2026-06-22).** Fixed the 5 broken `overview/* → cad/*/README.md`
  links (use absolute `/docs/cad/<name>`; Docusaurus strips the numeric dir prefix), set
  `onBrokenLinks: 'throw'` so `pnpm build` fails on broken links, and added a `ci.yml` build-check
  on PRs / branch pushes.
- Optional **version-drift guard**: a grep check that fails when a published surface disagrees
  with the released `pom.xml` / package version.

---

## 4. Completed

### 2026-06-19 — golden path + SDK accuracy

- **Golden-path restructure** across the three surfaces (§2): Sandbox headline, SDK/peer/Lisp
  cards, fixed runnable testnet examples.
- **Python SDK renamed and re-released.** PyPI distribution `convex-api-py` → **`convex-sdk`**,
  import `convex_api` → **`convex_sdk`** (GitHub repo stays `convex-api-py`). Set up PyPI
  trusted-publishing CI and released **0.3.3**, which also fixed two response-model bugs that
  broke the faucet/transact flow against the testnet (found via live test). The quickstart's
  Python path now works on the published package.
- **Naming sweep** across 9 design doc pages (`convex-api`/`convex_api` → `convex-sdk`/
  `convex_sdk`), preserving GitHub-repo URLs and the `ConvexAPIError` class.
- **convex.ts**: fixed the publish workflow (build before test — every `@dev` snapshot had been
  failing); documented the asset/CNS/account handles + `MemoryKeyStore`; removed an orphaned
  `jest.config.js`.
- **convex-java**: asset helpers (`Fungible`, `TokenBuilder`) now use the `@convex.fungible/…`
  CNS path instead of the `import` anti-pattern (verified live); documented the helpers.
- **convex.cljc**: flagged as requiring an update for the current Convex version (it targets
  0.7.11) and excluded from the supported SDK set until revived.

### 2026-06-15 — quick wins

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

## 5. Deferred / parked (with rationale)

- **CLI reference generation** — the `convex-cli` README command table is hand-maintained and will
  drift; generate it from picocli *if/when* that becomes a maintenance pain.
- **`CONTRIBUTING.md` quickstart** — a short "clone → `mvn install` → `mvn test` → module map →
  good-first-issue" guide would help code contributors. New surface; draft on request.
- **Docs versioning** — users on an old jar read HEAD docs. Revisit when the public API moves.
- **Notarized native installers** — decided **no**: the audience can run a jar, so we *documented*
  the OS first-run prompt instead. Revisit signing/notarisation only if Desktop becomes a featured
  distribution (and gate any notarisation to release branches — it has been slow/expensive before).

---

## 6. Strengths to protect (don't regress)

- **`README.md`** — clear value prop, honest comparison table, one-line install + multiple options.
- **`convex-cli/README.md`** — task-oriented use-cases, copy-pasteable commands, documented exit codes.
- **`convex-java/README.md`** — connect → account → query → transact → async, with a thread-safety caveat.
- **Install scripts** — Java-version check, writable-PATH handling, `CONVEX_VERSION`/`CONVEX_HOME`.
- **Docs** — coherent role-based learning path, a genuinely beginner-friendly "Gentle Lisp
  Introduction", and a self-contained Java local-peer quickstart that produces real output.
- **convex.world** — a real live Sandbox/REPL against an actual peer, and a strong Developers menu.

## 7. Success measures

- **Time-to-first-result** < 5 minutes, one page, no dead links. — *met: the Sandbox headline is a
  sub-minute first result.*
- **Smart-contract on-ramp exists** — a reader can deploy a trivial actor without leaving the path.
  — *met: the headline deploys and calls a `^:callable` actor.*
- **Single front door** — every "getting started" surface routes to the same golden path. — *met:
  quickstart, README, and hero all lead with the Sandbox.*
- **Zero broken links** from any README/doc (add a CI link-check). — *partial: the quickstart is
  clean; three pre-existing `overview/*` links remain (see §3).*

---

*Lessons logged:*
- *Verify doc links with `pnpm build`, not by reasoning about paths — `design` uses
  `trailingSlash: false` (relative links resolve against the parent dir), custom page `slug`s, and
  `onBrokenLinks: 'warn'` (broken links don't fail the build).*
- *Live-test SDKs against a real venue before documenting a flow — the public testnet caught
  faucet and transaction-hash response-model bugs in the Python SDK that unit tests and reasoning
  both missed.*
