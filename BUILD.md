# Build instructions

## Overview

Convex main repository is structured as a multi-module Maven project.

## Automated Builds

### Snapshot Builds (develop branch)

Every push to the `develop` branch triggers an automated snapshot build:
- Builds and tests the project
- Creates/updates a `snapshot-develop` pre-release on GitHub
- Uploads `convex.jar` with current version and commit info
- Marked as pre-release (unstable, for development/testing only)

Access: [Snapshot Release](https://github.com/Convex-Dev/convex/releases/tag/snapshot-develop)

### Release Builds (tagged versions)

Pushing a tag matching `*.*.*` (e.g., `0.8.3`) triggers an automated release build:
- Builds and tests the project
- Extracts changelog from CHANGELOG.md
- Creates a stable GitHub Release
- Uploads `convex.jar` as a release asset
- Marked as stable production release

See "Release process" section below for the full workflow.


## Release process

### 1. Ensure clean build

```bash
mvn -B clean install
```

All tests must pass, including headless (no GUI) — the CI server runs on headless Linux.

### 2. Update CHANGELOG

- Finalise the `CHANGELOG.md` section for the current version
- Annotate with the release date
- Commit to `develop`

### 3. Merge to master

```bash
git checkout master
git merge develop --no-ff
```

### 4. Set version

```bash
mvn versions:set -DnewVersion='0.8.3'
git add -A && git commit -m "Prepare for Release 0.8.3"
```

### 5. Tag and push

```bash
git tag 0.8.3
git push origin master
git push origin 0.8.3
```

This triggers the GitHub Actions release workflow which builds, tests, and creates a GitHub Release with `convex.jar` attached.

### 6. Confirm GitHub Release is live

**Wait for the release workflow to complete successfully** before proceeding. Check at:

https://github.com/Convex-Dev/convex/releases

Verify:
- Release status is not draft/pre-release
- `convex.jar` is attached as an asset
- Changelog content is correct

If the workflow fails, fix the issue, re-tag, and re-push. Do **not** proceed to Maven Central until the GitHub Release is confirmed live.

### 7. Deploy to Maven Central

Only after confirming the GitHub Release is live:

```bash
git checkout master
mvn deploy -Prelease
```

This signs all artifacts with GPG and uploads to Maven Central via the Sonatype Central Publishing plugin. Requires GPG signing key and Maven Central credentials configured locally.

### 8. Prepare next development version

```bash
git checkout develop
git merge master --no-ff
mvn versions:set -DnewVersion='0.8.4-SNAPSHOT'
```

- Add new "Unreleased" section to `CHANGELOG.md`
- Commit and push `develop`

## Docker

Docker images are built and pushed to Docker Hub automatically as part of the release workflow. Each release produces:

- `convexlive/convex:<version>` (e.g. `convexlive/convex:0.8.3`)
- `convexlive/convex:latest`

Images are multi-architecture (linux/amd64 and linux/arm64).

### Docker Hub secrets

The release workflow requires two secrets configured in the GitHub repository:

- `DOCKERHUB_USERNAME` — Docker Hub username
- `DOCKERHUB_TOKEN` — Docker Hub access token (not password)

### Local Docker build

To build locally for testing:

```bash
docker build -t convexlive/convex:latest .
```

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
