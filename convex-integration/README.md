# Convex Integration

This module is used for contructing primary Convex artifacts and integration testing.

## `convex.jar` construction

The `convex-integration` module builds `convex.jar` as a fat jar with dependencies for convenient distribution and usage (e.g. at the CLI)

## Integration Tests

To run live integration tests do:

```bash
mvn verify -Pintegration-tests
```