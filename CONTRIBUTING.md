# Contributing to Convex

Thanks for your interest in contributing to Convex! This guide covers how to build the
project, run the tests, and submit changes.

For an overview of the codebase and module layout, see [AGENTS.md](AGENTS.md) and the
per-module `README.md` files. For build and release details, see [BUILD.md](BUILD.md).

## Licensing and Contributor Agreement

Contributions are accepted under the [Convex Public License](LICENSE.md). Contributors
retain copyright but must accept the licence terms, and a Contributors Agreement is
required for all submissions to the core repository. By opening a pull request you
confirm that you have the right to contribute the code and agree to these terms.

## Prerequisites

- **Java 21+** (JDK)
- **Maven 3.7+**

Maven is not vendored via a wrapper, so install a compatible version yourself.

## Building

A full build with local install:

```bash
mvn clean install
```

To build a single module and its dependencies:

```bash
mvn install -pl convex-core -am
```

> **IDE note:** some IDEs (including Eclipse) don't automatically pick up the ANTLR4
> generated sources. If you see unresolved parser classes, add
> `target/generated-sources/antlr4` as a source directory. See [BUILD.md](BUILD.md) for
> details.

## Running Tests

Run the whole test suite:

```bash
mvn test
```

Run the tests for a single module (much faster while iterating):

```bash
mvn test -pl convex-core
```

If the module depends on changes in other modules you haven't installed yet, add `-am`
to build those first:

```bash
mvn test -pl convex-restapi -am
```

Tests **must pass headless** (no GUI / no `$DISPLAY`), because CI runs on a headless
Linux runner. Avoid writing tests that require a display or open network access to
external services.

## Branch Strategy

- **`develop`** — active development; the default branch and the target for pull requests.
- **`master`** — release branch; updated as part of the release process only.
- **Feature branches** — branch from `develop` and open your PR back against `develop`.

## Coding Conventions

- **British English** spelling throughout (e.g. *decentralised*, *behaviour*).
- **Immutable data** — work with the immutable lattice data structures (the `ACell`
  hierarchy). Avoid introducing mutable shared state.
- **Security mindset** — peer and REST API surfaces are public by default and must be
  robust against malicious input.
- **Terminology** — use the canonical Convex vocabulary. Don't substitute blockchain
  terms ("gas", "fees", "block", "miner", "validator", "mainnet", etc.). See the
  [glossary](https://docs.convex.world/tutorial/glossary). Key terms include CVM coin,
  Juice, Copper, Peer, Actor, Lattice, CPoS, Belief, Etch, CAD, CNS, and Protonet.
- Match the style of the surrounding code (naming, formatting, comment density).

## Pull Request Checklist

Before opening a PR, please make sure:

- [ ] The full build passes locally: `mvn clean install`.
- [ ] New behaviour is covered by tests, and all tests pass headless.
- [ ] The branch targets `develop`.
- [ ] User-visible changes are noted in the `[Unreleased]` section of
      [CHANGELOG.md](CHANGELOG.md), referencing the relevant issue number.
- [ ] Spelling follows British English and terminology follows the glossary.

## Reporting Bugs

Open a [GitHub issue](https://github.com/Convex-Dev/convex/issues) with a clear
description, the affected module and version, and reproduction steps where possible.

**Security vulnerabilities** should **not** be filed as public issues — please follow
the private disclosure process in [SECURITY.md](SECURITY.md).

## Community

- **Discord:** https://discord.gg/5j2mPsk
- **Email:** info(at)convex.world

We're happy to help you get started — don't hesitate to ask questions on Discord.
