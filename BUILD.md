# Build instructions

## Overview

Convex main repository is structured as a multi-module Maven project.


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

### Tag release

- Tag Release Commit e.g. `0.8.2`

### Build and deploy

```
mvn clean deploy 
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