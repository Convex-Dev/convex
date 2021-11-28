# Build instructions

## Overview

Convex min repository is structured as a multi-module Maven project.

## Build and test

```
mvn clean install
```

## Release preparation

### Set version information

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