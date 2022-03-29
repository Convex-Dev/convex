# convex-java
Java client library for Convex

## About

Convex is an open, decentralised technology for the Internet of Value. Java is one of the world's leading programming languages, especially in the realm of business and finance.

`convex-java` provides everything Java developers need to access Convex and utilise all of its capabilities from their own applications.

## Usage

You will need a connection to a peer on the Convex Network. Peers are servers which participate in the maintaining consensus on the Convex Network, by executing the CPoS consensus algorithm and validating transactions. It is easiest if you simply use the free public peer available at `https://convex.world`.

```java
Convex convex = Convex.connect("https://convex.world");
```

To utilise the network, you will need an account with available funds. On the Test Network, you can obtain one by requesting a new account with free balance (up to 10,000,000 Convex copper coins). This should be enough for most simple testing.

```java
convex.useNewAccount(10000000);
```

`convex-java` will automatically generate a new cryptographic key pair to secure your new account. Having a valid key pair for the account is the *only way* to successfully submit transactions for that Account on the Convex Network. If you want to access the key pair, you can use:

```java
convex.getKeyPair()
```





## Installation and Configuration

You can clone this repository and run `mvn install` to get a working local version. This is recommended for developers who wish to use early snapshot versions or contribute to `convex-java` as an open source project.

`convex-java` is also available as a Maven dependency.

```
<dependency>
	<groupId>world.convex</groupId>
	<artifactId>convex-java</artifactId>
	<version>0.0.1</version>
</dependency>
```

## License

Copyright 2021 The Convex Foundation

`convex-java` is licensed under the Apache License v2.0. 

Some dependencies from The Convex Foundation are licensed under the Convex Public License, which is an open source license that is free to use for any applications using the Convex Network.
