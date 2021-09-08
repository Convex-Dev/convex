# Build instructions

## Overview

Convex min repository is structured as a multi-module Maven project.

## Build and test

```
mvn clean install
```

## Release preparation

Set version information

```
mvn versions:set -DnewVersion='0.7.0-rc3'
```

Build and deploy

```
mvn clean deploy -DperformRelease
```