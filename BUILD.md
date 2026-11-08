# Build instructions

## Overview

Convex main repository is structured as a multi-module Maven project.

## Fast local iteration

Use the Maven wrapper so local builds use the repository's pinned Maven
version. Avoid `clean` while iterating: it deliberately discards every compiled
class and generated source across all modules.

Commands below use the Unix/Git Bash wrapper spelling. In Windows PowerShell,
replace `./mvnw` with `.\mvnw.cmd` and keep the remaining arguments unchanged.

```bash
# Compile and test one changed module plus its dependencies
./mvnw -B -T1C test -pl convex-peer -am

# Incrementally compile and test the complete reactor
./mvnw -B -T1C test
```

Use `./mvnw -B clean verify` for final verification. `install` also copies the
artifacts into `~/.m2`, where other local projects resolve them, so keep it for
releases and for a sibling project that needs the fresh snapshot. Ordinary
builds do not generate source/Javadoc jars or load Maven Central publishing;
`-Prelease` enables those release-only artifacts and services.

### Core test execution

Core tests run in one JVM. Jupiter runs classes and methods concurrently with
separate test instances; classes that need shared setup declare `PER_CLASS`
explicitly. Vintage runs the existing JUnit 4/quickcheck classes concurrently.
Ordinary persistence tests reuse `Samples.TEST_STORE` and `TEST_STORE2`; private
stores are reserved for lifecycle, GC, configuration and exact-content tests.

The everyday run covers representative Etch lifecycle configurations and a
complete rebuild. Exhaustive lifecycle and physical-rebuild format matrices run
in CI and release builds. To include them locally:

```bash
./mvnw -B test -pl convex-core -am -Dconvex.test.extended=true
```

In Eclipse, add `-Dconvex.test.extended=true` to the JUnit launch's VM arguments
for the exhaustive run; omit it for everyday testing. No tests are forked into
separate processes to obtain concurrency.

### Property tests

Core property tests use the existing `junit-quickcheck` generators and runners,
executed by the JUnit 6 Vintage engine alongside the Jupiter tests. Classes are
named `*PropertyTest` so ordinary Maven test runs discover them.

```bash
./mvnw -B test -pl convex-core -am '-Dtest=*PropertyTest' -Dsurefire.failIfNoSpecifiedTests=false
```

Quickcheck retains sized generation through `GenerationStatus.size()`, trial
counts through `@Property(trials=...)`, and its existing shrinkers. Failures
report the generated arguments and a seed for each parameter. To reproduce a
failure, temporarily add `@When(seed=...)` from `com.pholser.junit.quickcheck` to
each generated parameter using the reported seeds, then rerun the property.
Keep the generator and trial settings unchanged during replay. Add a small
deterministic regression for a confirmed bug while retaining fresh random
generation in the property suite.

### Test hazards and diagnosis

Small `CVMLong`s, booleans, empty collections and core functions are cached cells
shared across the test JVM. Never write one under a synthetic Etch key or a fake
hash: that corrupts its hash for every later test in the JVM. Use fresh, randomly
seeded blobs of about 30 bytes instead.

To reproduce an intermittent failure, loop it in one JVM under CPU load in a soak
test gated by `-Dconvex.soak=true`, as `JoinNetworkSoakTest` does.

A test run that hangs at near-zero CPU is a class-initialisation deadlock: run
`ClassInitCycles` (`convex-integration/src/test/java/convex/tools`) to find the
cycle.

## CI Workflows

Four GitHub Actions workflows handle continuous integration:

### Build (`build.yml`)

Runs on every push to any branch, and on pull requests (including from forks). Builds the project and runs all tests. Superseded runs on the same branch/PR are cancelled automatically.

### CodeQL (`codeql.yml`)

Static security analysis for the Java codebase (tests run separately in `build.yml`). Runs on pushes and pull requests targeting `develop` and `master`, plus a weekly scheduled scan. Superseded analyses for the same ref are cancelled automatically.

### Release (`release.yml`)

Run by hand on `master` with the version to release (step 6). Builds and tests that commit, then creates the version tag and a GitHub Release with `convex.jar` attached, together with its SHA-256 checksum, an SBOM and a signed build-provenance attestation (verifiable with `gh attestation verify convex.jar -R Convex-Dev/convex`). It also packages the tested build into the Maven Central bundle, unsigned and not uploaded, and attaches it (`convex-<version>-central-bundle.zip`, attested too) for step 8 to sign and publish. It does not publish Docker images; step 10 does that by hand.

### Docker (`docker.yml`)

Manual-dispatch only: builds a Docker image and pushes it to Docker Hub under the given tag, and also as `latest` when that box is ticked. Use after each release (step 10), for snapshot builds or for ad-hoc re-publishing.

- Snapshot: go to Actions > Docker > Run workflow, set tag to `snapshot`
- Or via CLI: `gh workflow run docker.yml --ref <branch-or-tag> -f tag=<tag>`, adding `-f latest=true` to move `latest` too

Requires two secrets configured in the GitHub repository:

- `DOCKERHUB_USERNAME` — Docker Hub username
- `DOCKERHUB_TOKEN` — Docker Hub access token (not password)

## Release process

### 1. Ensure clean build

```bash
./mvnw -B clean install
```

All tests must pass, including headless (no GUI) — the CI server runs on headless Linux.

Also confirm the latest CI run on `develop` is green before proceeding (a local build
does not exercise the headless-Linux environment the release workflow uses):

```bash
gh run list --branch develop --limit 1
```

### 2. Update CHANGELOG

The changelog is for users and operators: people running nodes and services, and
clients using SQL, JDBC, PostgreSQL, WebDAV, REST or the CLI. Entries are written
this way as changes land, and the unreleased section is cleaned up before each
release:

- Each entry is a short, positive headline in the imperative ("Support…", "Speed
  up…", "Keep…"), with at most one link, to its doc or issue.
- Related entries are merged. Implementation detail stays out: class, method and
  constant names, dependency bumps, build plumbing, test-only refactors and agent
  tooling. Embedders read the code, so Java API changes share one entry pointing
  at the design notes.
- A breaking change gets one entry pointing at its doc.

Then:

- Rename the `## [X.Y.Z-SNAPSHOT] - Unreleased` heading to `## [X.Y.Z] - YYYY-MM-DD` (exact format — the release workflow parses the section header by `## [<version>]`, so the version and heading must match).
- Commit to `develop` and push it. The release is made from `origin/develop`.

### 3. Merge to master in a release worktree

The release runs in its own worktree on `master`. The main checkout stays on
`develop` for everyone else working in it. Steps 4 to 7 run in this worktree:

```bash
git fetch origin
git worktree add ../convex-release master
cd ../convex-release
git merge --ff-only origin/master
git merge --no-ff origin/develop
```

Merging `origin/develop` releases what was pushed and built by CI, and nothing
committed locally since.

### 4. Set version

```bash
./mvnw -B versions:set -DnewVersion='0.8.4' -DgenerateBackupPoms=false
git add pom.xml '**/pom.xml' && git commit -m "Prepare for Release 0.8.4"
```

`./mvnw versions:set` only rewrites `pom.xml` files — stage those explicitly rather than `git add -A`, which would sweep in any unrelated working-tree changes (stray `.env` files, editor scratch files, partial WIP).

As part of the same version-bump commit, also update:

- **`project.build.outputTimestamp`** in the parent `pom.xml` — `versions:set`
  refreshes it to the current UTC time (e.g. `2026-04-18T16:08:52Z`); check that the
  diff shows the new value. It is frozen for reproducible builds, so a stale value
  would give the new artifacts the previous release's timestamp.
- **Module README install snippets** — the Maven/Gradle `<version>` examples in the
  module `README.md` files should reference the new release version:

  ```bash
  # from the repo root (Git Bash / Linux / macOS)
  grep -rl --include=README.md "<previous-version>" . | xargs sed -i 's/<previous-version>/<new-version>/g'
  ```

- **Downstream docs and website** (separate repos) each hold the version in one constant, which
  must be bumped in lockstep, otherwise onboarding goes stale:
  - **`design`** — `CONVEX_RELEASE_VERSION` in `src/data/release.mjs`, which fills in the
    `convex-java` coordinates and `convex.jar` download links across the docs (plain `x.y.z`,
    with no `v` prefix). Check the pages that use it still match the release.
  - **`convex.world`** — `CONVEX_RELEASE_VERSION` in `src/data/release.ts`, shared by
    the footer and Downloads page (plain `x.y.z`, with no `v` prefix). Deploy the
    website update after the GitHub release is available.

### 5. Rebuild and smoke test the built jar

**Rebuild first** — the jar from step 1 was built *before* `versions:set`, so it still
carries the snapshot version. Smoke-testing it would validate the wrong artifact:

```bash
./mvnw -B clean install
java -jar convex-integration/target/convex.jar --version
```

Verify the reported version is the release version (e.g. `0.8.4`, not `-SNAPSHOT`).
The `install` also puts the release into `~/.m2`, where other local projects can
use it before Maven Central has it.

Quick last-line-of-defence check: the uberjar launches, main class resolves, CLI wiring is intact. Maven Central publishes are irrevocable, so catch uberjar class-path regressions now.

### 6. Push master and run the release workflow

```bash
git push origin master
gh workflow run release.yml --ref master -f version=0.8.4
```

The workflow checks that the version matches the poms, has a changelog section and
is not yet tagged, then builds and tests `master`. Only when all of that passes does
it create the tag and the GitHub Release, on the commit it built. A ruleset stops
release tags being moved or deleted, so a tag is final once created.

### 7. Confirm GitHub Release is live

**Wait for the release workflow to complete successfully** before proceeding. Check at:

https://github.com/Convex-Dev/convex/releases

Verify:
- Release status is not draft/pre-release
- `convex.jar` is attached as an asset, along with its SHA-256 checksum (`convex.jar.sha256`), the SBOM (`convex-<version>-sbom.json`) and the unsigned Maven Central bundle (`convex-<version>-central-bundle.zip`)
- Build provenance verifies against the downloaded jar: `gh attestation verify convex.jar -R Convex-Dev/convex` (it may print nothing on success: exit code 0 means verified, or add `--format json`)
- The release text is the version's changelog section.

Do **not** proceed to Maven Central until the GitHub Release is confirmed live.

#### If the release workflow fails

A failed run creates no tag and no release. Find the cause, fix it on `develop`,
merge it into the release worktree's `master` as in step 3, push `master` and run
the workflow again. A failure outside the commit, such as a runner fault, needs only
`gh run rerun <run-id>`.

### 8. Deploy to Maven Central

Only after confirming the GitHub Release is live, from any checkout, in a shell whose
`gpg` holds the signing key (on Windows, PowerShell with Gpg4win rather than Git Bash):

```bash
java scripts/PublishToCentral.java 0.8.4
```

This publishes the bundle the release workflow built and tested, so Central receives
exactly those files, while the signing key never leaves this machine. The tool:

- downloads the release's `convex-<version>-central-bundle.zip` and checks its
  attestation shows the release workflow built it from the commit the tag names;
- checks every file in it belongs to that version;
- signs each file with GPG, and uploads the result through Central's Publisher API;
- returns once Central reports the deployment published.

It needs `gh`, the GPG signing key and Maven Central credentials (server `central`
in `~/.m2/settings.xml`, not encrypted). Run it interactively: GPG needs to open
pinentry for the signing-key passphrase.

If Central rejects a deployment, nothing is published. Read the errors the tool
prints, drop the deployment on central.sonatype.com and run the tool again.

### 9. Prepare next development version

Remove the release worktree and, in the main checkout on `develop`:

```bash
git worktree remove ../convex-release
git merge master --no-ff
./mvnw -B versions:set -DnewVersion='0.8.5-SNAPSHOT' -DgenerateBackupPoms=false
git add pom.xml '**/pom.xml' && git commit -m "Prepare for next development cycle (0.8.5-SNAPSHOT)"
```

- Add new `## [0.8.5-SNAPSHOT] - Unreleased` section to `CHANGELOG.md` with empty `### Added` / `### Changed` / `### Fixed` subsections.
- Commit and push `develop`.

### 10. Publish the Docker image

The release workflow does not publish Docker images. Once the tag exists, build the
image from it and push it under the release version and `latest`, which the
website's Downloads page names:

```bash
gh workflow run docker.yml --ref 0.8.4 -f tag=0.8.4 -f latest=true
```

## Docker

The `Dockerfile` is a self-contained multi-stage build. No pre-built artifacts needed.

### Local Docker build

```bash
docker build -t convexlive/convex:latest .
```

### Push to Docker Hub

```bash
docker login -u convexlive docker.io
docker push convexlive/convex:latest
```

Or use the automated Docker workflow as described in "CI Workflows" above.

## JPackage Build

For a deployable application build

```bash
jpackage -n convex --main-class convex.main.Main --main-jar convex.jar
```

## Repeatable Builds

It is the intention that builds should be repeatable. Any issues, please report!

## Known Issues and Fixes

### ANTLR Generated Sources

Some IDEs (including Eclipse) may not automatically recognise the source directory for generated ANTLR4 source files. A manual fix is to add "target/generated-sources/antlr4" as a source directory in the project build path.
