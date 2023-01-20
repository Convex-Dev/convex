# Convex-Sodium

This module provides integration for the native Sodium crypto libraries in Convex.

Sodium libraries are approx. 50% faster than the pure JVM equivalents, so there is some performance benefit from using Sodium, at the cost of more complex native code dependencies and longer startup time.

## Usage

You can switch Convex to use the Sodium provider with the following code:

```java
convex.core.cropto.SodiumProvider.install();
```

This will cause the Sodium native libraries to be loaded, and subsequent keypairs used for signing and signature verification will use Sodium.

## Important Notes

1. Sodium uses native libraries. This may not work in some contexts and configurations.
 