# Build instructions

## Overview

Convex main repository is structured as a multi-module Maven project.

## CI Workflows

Three GitHub Actions workflows handle continuous integration:

### Build (`build.yml`)

Runs on every push to any branch. Builds the project and runs all tests.

### Release (`release.yml`)

Triggered when a version tag is pushed (e.g. `0.8.3`). Builds, tests, and creates a GitHub Release with `convex.jar` attached.

### Docker (`docker.yml`)

Builds a Docker image and pushes to Docker Hub. Triggered automatically when a GitHub Release is published, or manually via workflow dispatch for snapshot builds.

- Release: pushes `convexlive/convex:<version>` and `convexlive/convex:latest`
- Snapshot: go to Actions > Docker > Run workflow, set tag to `snapshot`

Requires two secrets configured in the GitHub repository:

- `DOCKERHUB_USERNAME` — Docker Hub username
- `DOCKERHUB_TOKEN` — Docker Hub access token (not password)

## Release process

### 1. Ensure clean build

```bash
mvn -B clean install
```

All tests must pass, including headless (no GUI) — the CI server runs on headless Linux.

### 2. Update CHANGELOG

- Rename the `## [X.Y.Z-SNAPSHOT] - Unreleased` heading to `## [X.Y.Z] - YYYY-MM-DD` (exact format — the release workflow parses the section header by `## [<version>]`, so the tag and heading must match).
- Finalise the `### Added` / `### Changed` / `### Fixed` bullets for this version.
- Commit to `develop`.

### 3. Merge to master

```bash
git checkout develop && git pull --ff-only
git checkout master && git pull --ff-only
git merge develop --no-ff
```

The `pull --ff-only` on both branches ensures you're not merging a stale local develop or pushing a stale master.

### 4. Set version

```bash
mvn versions:set -DnewVersion='0.8.4'
git add pom.xml '**/pom.xml' && git commit -m "Prepare for Release 0.8.4"
```

`mvn versions:set` only rewrites `pom.xml` files — stage those explicitly rather than `git add -A`, which would sweep in any unrelated working-tree changes (stray `.env` files, editor scratch files, partial WIP).

### 5. Smoke test the built jar

```bash
java -jar convex-integration/target/convex.jar --version
```

Quick last-line-of-defence check: the uberjar launches, main class resolves, CLI wiring is intact. Maven Central publishes are irrevocable, so catch uberjar class-path regressions now.

### 6. Tag and push

```bash
git tag 0.8.4
git push origin master
git push origin 0.8.4
```

This triggers the release workflow which builds, tests, and creates a GitHub Release with `convex.jar` attached.

### 7. Confirm GitHub Release is live

**Wait for the release workflow to complete successfully** before proceeding. Check at:

https://github.com/Convex-Dev/convex/releases

Verify:
- Release status is not draft/pre-release
- `convex.jar` is attached as an asset
- Changelog content is correct (not the fallback "See CHANGELOG.md for details" — if that shows, the changelog section header didn't match the tag and the workflow should have failed; investigate).

Do **not** proceed to Maven Central until the GitHub Release is confirmed live.

#### If the release workflow fails

Fix the underlying issue on master, then delete and re-push the tag:

```bash
# Delete locally and on remote
git tag -d 0.8.4
git push origin :refs/tags/0.8.4

# Re-tag on the fixed commit and push
git tag 0.8.4
git push origin 0.8.4
```

A stray GitHub Release created by a failed workflow run also needs to be deleted from the Releases page — `softprops/action-gh-release` will refuse to overwrite a release with the same tag name.

### 8. Deploy to Maven Central

Only after confirming the GitHub Release is live:

```bash
git checkout master
mvn deploy -Prelease
```

This signs all artifacts with GPG and uploads to Maven Central via the Sonatype Central Publishing plugin. Requires GPG signing key and Maven Central credentials configured locally.

### 9. Prepare next development version

```bash
git checkout develop
git merge master --no-ff
mvn versions:set -DnewVersion='0.8.5-SNAPSHOT'
git add pom.xml '**/pom.xml' && git commit -m "Prepare for next development cycle (0.8.5-SNAPSHOT)"
```

- Add new `## [0.8.5-SNAPSHOT] - Unreleased` section to `CHANGELOG.md` with empty `### Added` / `### Changed` / `### Fixed` subsections.
- Commit and push `develop`.

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
