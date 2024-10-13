# Changelog
Notable changes to Convex core modules will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## Unreleased 

### Added

- Better CNS functionality
- Support for tagged forms in Convex Reader
- `dissoc-in`, `update` and `update-in` core functions
- `switch` conditional macro
- Tagged values in Reader (e.g. `#Index {}`)
- `evict-peer` core function to remove old / understaked peers
- Automatic distribution of rewards to peers / delegated stakers
- Generalised CAD3 data support

### Changed

- Class hierarchy refactoring for `convex.core.cpos`
- More GUI updates
- Reader performance enhancements
- Booleans no longer cast to the Integers 0 / 1
- Update some errors thrown for failed casts
- `set!` now allows pending definitions
- Better internal handling of peer fees
- Rename `stake` to `set-stake`

## [0.7.15] - 2024-09-17

### Added
- Docker `Dockerfile` build for self-contained peer container

### Changed
- Better format / HTML building for peer web app with j2html
- Better CLI design for `convex account balance` and `convex account info`
- Better handling for JSON results in REST client

## [0.7.14] - 2024-09-10 - MAVEN CENTRAL ISSUE

NOTE: Due to to an apparent issue in Maven Central, this release was only partially uploaded. It is recommended to avoid depending upon this release.

### Added
- New main class for both GUI and desktop ("MainGUI")
- New `convex-integration` module
- DLFS base implementation and browser
- Better Result and `log` information

### Changed
- Updated CLI and GUI functionality
- Significant internal refactoring
- Upgrades to default REST API and OpenAPI documentation
- Better error handling
- Convert core modules to JPMS

## [0.7.13] - 2024-05-21

### Added
- New Convex Deskup GUI interface ("MainGUI")
- BIP39 key generation support
- Observability support initial version
- Support for `@` and `resolve` for CNS resolution
- Extra special ops: `*nop*`, `*parent*`, `*controller*`, `*memory-price*` and `*env*`
- `query-as` function (query mode equivalent to `eval-as`)
- Convex Lisp `quasiquote` implementation
- Extra example code and tests
- Data Lattice File System (Prototype)

### Changed
- Updated CLI and GUI functionality
- Significant internal refactoring
- Encoding changes for better memory efficiency
- Upgrades to default REST API
- More efficient signing protocol / SignedData compressed format
- CNS improvements
- More efficient encodings for CVM core definitions and ops

## BREAKING CHANGES
- Switch from `:callable?` to `:callable` for metadata on callable actor functions
- Etch encoding changes. Will require fresh Etch database. 
- Renamed `BlobMap` to `Index`

### Fixed
- Miscellaneous edge cases and error handling
- Bug fixes for message decoding

## [0.7.12] - 2023-07-12

### Added
- Asset ownership based trust monitor `convex.trust.ownership-monitor`
- Peers now utilise quick Belief broadcasts (own Order changes only)
- Basic fork detection and recovery from historical states

### Changed
- Account controllers can now be any scoped actor
- Various adjustments to improve CPoS latency
- Experimental adjustments to CVM constants

### Fixed
- Bug fixes for message decoding

## [0.7.11] - 2023-05-30

### Added
- Scoped Actors 
- BIP39 compatible seed generation
- Variable sized Etch index levels
- Ability to set `*juice-limit* in a Context (thanks @helins!)
- Extra consensus confirmation levels and configuration options

### Changed
- Make `blob` casts support arbitrary sized integers
- Remove unnecessary generic type parameter from Context class
- Updates to GUI implementation
- `*juice*` now starts at 0 and counts upwards towards a juice limit

### Fixed
- Various improvements for efficient consensus

## [0.7.10] - 2023-04-28

### Added
- Support for arbitrary sized integers (Part 1)
- Better support for CI builds
- Allow `merge` and `slice` to work with Indexs

### Changed
- General update of dependencies to most recent versions as of Apr 2023
- Refactoring of Sodium crypto libraries to separate convex-sodium module
- Sequence numbers are now incremented at end of transaction. *sequence* behaves "as-if" already updated.
- New networking message model

### Fixed
- Fix for printing of single quotes (see #407)
- Fixed most Javadoc warnings
- Fixed issue with encoding of `set!` Op
- Tighten casting behaviour
- Better management of message queues and server threads

## [0.7.9] - 2022-09-22
### Fixed
- Fix for Java 11 compatibility with Etch

## [0.7.8] - 2022-09-13
### Fixed
- Refactoring Etch seekMap for Java 11 support see #394
- Avoid static initialisation for executor thread pool used in stress testing

## [0.7.7] - 2022-09-05
### Added 
- REST API Server
- Support for parameterised asset paths in `convex.asset` as per CAD19
- Multi-token reference implementation for single actor supporting many fungible assets
- Add missing `double?` predicate
- OpenAPI REST specification

### Changed
- Static compilation enabled for `convex.core` functions
- Better JSON utility support

### Fixed
- Correct handling for negative zero in min and max 
- Fixed handling for octal and unicode escape sequences in Reader

## [0.7.6] - 2022-05-24
### Added 
- `print` core function for readable representations
- `split` and `join` core functions for Strings
- `slice` core function
- Add `VectorBuilder` utility class for fast Vector construction
- `declare` core macro
- Additional benchmarks
- Mnemonic refactoring, add BIP39 word list

### Fixed
- Edge cases around UTF-8 string handling

## [0.7.5] - 2022-03-30
### Added 
- Adversarial test cases for Encodings
- Efficient BlobBuilder utility class

### Changed
- Convert CVM Characters to be Unicode code points
- Convert CVM Strings to be UTF-8 (backed by Blobs)
- Import convex-java as a submodule

### Fixed
- Miscellaneous edge cases with canonical encodings
- Update logback dependency to fix potential security issues
- Better validation for canonical Cells and Refs

## [0.7.4] - 2022-02-18
### Changed
- Require all Blocks in an Order to be Signed
- Support `empty?` predicate on all `Countable` CVM values
- Update `Block` format to remove Peer Key (get this from Signature)

### Fixed
- Catch NIOServer CancelledKeyException on Linux (thanks Otto!)

## [0.7.3] - 2021-11-28
### Added
- Constant compilation for `:static` declarations in core / other libraries

### Changed
- Additional validation for message formats

### Fixed
- Make `empty?` work on all Countable data types

## [0.7.2] - 2021-11-01
### Added
- Set can now be constructed with any Countable

### Changed
- Convex.queryXXX methods now return a CompletableFuture instead of Future
- Some Juice cost adjustments
- `empty?` now works on any Countable structure (including Strings and Blobs)
- `RefSoft` instances now directly reference a store instead of relying on thread locals
- Miscellaneous internal refactoring for Peers

### Fixed
- Eliminate non-canonical NaN values

## [0.7.1] - 2021-09-28
### Added
- Server now generates a keypair automatically if required
- Added `for-loop` for imperative C-style looping
- Support casting Longs <-> Blobs
- Bitwise Long operations bit-and, bit-or, bit-xor and bit-not
- Convenience overloads for Convex client API query and transact with String values

## Fixed
- Fix for Etch data length persistent issue


## [0.7.0] - 2021-09-08
### Added
- Initial Public Alpha release
- Core CVM
- Convergent Proof Of Stake Consensus
- Command Line Interface (CLI)
- GUI Testing Interface
- Benchmark Suites


