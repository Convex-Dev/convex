# Build instructions

## Overview

Convex main repository is structured as a multi-module Maven project.

## Build and test

```
GraphicsEnvironment.isHeadless()
```

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

### SCM release

- Merge to `master` branch
- Tag Release Commit
- Push to GitHub!

### Prepare for next develop version

- Merge `master` back into `develop`
- Create new CHANGELOG "Unreleased" section for next version
- Run `mvn versions:set -DnewVersion='0.7.4-SNAPSHOT'` for next snapshot version as required 

## Repeatable Builds

It is the intention that builds should be repeatable. Any issues, please report!

## Known Issues and Fixes

### ANTLR Generated Sources

Some IDEs (including Eclipse) may not automatically recognise the source directory for generated ANTLR4 source files. A manual fix is to add "target/generated-sources/antlr4" as a source directory in the project build path.