# Build instructions

## Overview

Convex main repository is structured as a multi-module Maven project.

## Automated Builds

### Snapshot Builds (develop branch)

Every push to the `develop` branch triggers an automated snapshot build:
- Builds and tests the project
- Creates/updates a `snapshot-develop` pre-release on GitHub
- Uploads `convex.jar` with current version and commit info
- ⚠️ Marked as pre-release (unstable, for development/testing only)

Access: [Snapshot Release](https://github.com/Convex-Dev/convex/releases/tag/snapshot-develop)

### Release Builds (tagged versions)

Pushing a tag matching `*.*.*` (e.g., `0.8.3`) triggers an automated release build:
- Builds and tests the project
- Extracts changelog from CHANGELOG.md
- Creates a stable GitHub Release
- Uploads `convex.jar` as a release asset
- Marked as stable production release

See "Release preparation" section below for the full release process.


## Release preparation

### Ensure clean build

```
mvn -B clean verify
```

Remember to test headless (i.e. no GUI) e.g. with CI server.

### Update CHANGELOG

Make sure `CHANGELOG.md` is fully up to date before deploy

- Finalise CHANGELOG for current version
- Annotate with date
- Commit to release branch

### Merge to master

Switch to `master` branch.

Merge `develop` as a merge commit (“If a fast-forward, create a merge commit” in Eclipse)

### Set version information

Set the version number for the new version to be released

```
mvn versions:set -DnewVersion='0.8.2' -DartifactId=* -DgroupId=*
```

Commit as "Prepare for Release 0.8.2"

### Tag and push release

Tag the release commit and push to trigger automated build:

```bash
git tag 0.8.3
git push origin 0.8.3
```

This will trigger the GitHub Actions release workflow which:
- Builds and tests the project (`mvn -B clean verify`)
- Extracts changelog for this version from CHANGELOG.md
- Creates a GitHub Release with the changelog
- Uploads `convex.jar` as a release asset

The JAR will be available as a GitHub Release asset named `convex-{version}.jar`

### Manual deploy to Maven Central (if needed)

```bash
mvn -B clean verify
mvn deploy -Prelease
```

### Prepare for next develop version

- Merge `master` back into `develop` (again no FF)
- Create new CHANGELOG "Unreleased" section for next version
- Run `mvn versions:set -DnewVersion='0.8.3-SNAPSHOT'` for next snapshot version as required 

## Docker build

To build a peer container using docker in the current directory from the provided `Dockerfile`

```bash
docker build -t convexlive/convex:latest .
```

To deploy to docker hub:

```
docker login -u "convexlive" docker.io
docker push convexlive/convex
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