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

This triggers the release workflow which builds, tests, and creates a GitHub Release with `convex.jar` attached.

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
