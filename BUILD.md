# Build instructions

## Overview

Convex main repository is structured as a multi-module Maven project.


## Build and test

### Merge 

- Merge from `master` branch, ensure in sync
- Merge any other branches for release

### Ensure clean build

```
mvn clean package
```

Remember to test headless (i.e. no GUI) e.g. with CI server.

## Release preparation

### Set version information

First set the version number for the new version to be released

```
mvn versions:set -DnewVersion='0.7.3'
```

### Update CHANGELOG

Need to make sure `CHANGELOG.md` is fully up to date before deploy

- Finalise CHANGELOG for current version
- Annotate with date
- Commit to release branch


### Build and deploy

```
mvn clean deploy -DperformRelease
```

### Tag release

- Merge to `master` branch
- Tag Release Commit on merge
- Push to GitHub!

### Prepare for next develop version

- Merge `master` back into `develop`
- Create new CHANGELOG "Unreleased" section for next version
- Run `mvn versions:set -DnewVersion='0.7.4-SNAPSHOT'` for next snapshot version as required 

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

## Repeatable Builds

It is the intention that builds should be repeatable. Any issues, please report!

## Known Issues and Fixes

### ANTLR Generated Sources

Some IDEs (including Eclipse) may not automatically recognise the source directory for generated ANTLR4 source files. A manual fix is to add "target/generated-sources/antlr4" as a source directory in the project build path.