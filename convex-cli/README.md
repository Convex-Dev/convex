# Convex CLI

## Overview

The `convex-cli` module provides a CLI interface to Convex, including the ability to operate as either a client or peer on the Convex Network

## Usage Examples

### List available commands and options

```
convex --help
```

### Run a local develop testnet with a GUI

```
convex local start
```

### Run the main Convex Desktop GUI application

```
convex desktop
```

### Query the network

This runs the `*balance*` query to get the Convex Coin balance of account `#11`

```
convex query -a 11 *balance*
```

Queries are free, don't require a signature and you can query for any account on the network.

## Key Management

Operating Convex based systems correctly requires cryptographic keys for signing and verification. The CLI helpfully provides a number of useful tools for managing your keys.

### Listing keys

You can get a list of all public keys in the current key store as follows:

```
convex key list
```

By default, keys are store in the `.convex/keystore.pfx` file in the user's home director, but you can override this by either sertting the `CONVEX_KEYSTORE` environment variable or passing the `--keystore` option (works with most CLI commands).

### Generating a key pair

To execute transactions or operate peers on Convex you will need a cryptographic Ed25519 key pair.

Key pairs are stored in a PKCS #12 key store. By default this is stored in the user's home directory in the location `~/.convex/keystore.pfx`. The keystore encrypts keys so that they cannot be accessed without the correct passwords.

To generate a cryptographic key, it is recommended to use the following command:

```
convex key generate
```

This generates and outputs a BIP39 mnemonic phrase of 12 words consistent with the BIP39 standard. The words will look something like this:

```
evidence expand family claw crack dawn name salmon resource leg once curious
```

You will be also prompted for passwords including:
- A BIP39 passphrase. This is the passphrase that will be used to generate the private key
- A private key encryption password. This protects the new key by encrypting it in the key store

After the key pair is successfully generated, you will be able to use it providing have the private key password.

If the key is important to you, you will want to be able to recover it even if you permanently lose access to the key store (e.g. if your laptop is stolen, disk drive corrupted etc.). In this case, you SHOULD make sure you securely record the following:
- The BIP39 mnemonic word list
- The BIP39 passphrase

The command will output a 32 byte hex public key that will look something like this:

```
021efb3ff24898dffb30c9c7e490e86b2d0cb7a87c974a51894354532ff4670f
```

The public key MAY be shared publicly, and is important because it is used to:
- Identify your key pair in the key store
- Validate signatures made with the key pair (e.g. in Convex transactions)
- Create a Convex account protected by the key pair (as the "account key")
 
### Importing a key pair

To import a key pair from a BIP39 seed generated previously, you can use the following command:

```
convex key import --type=bip39 --text='evidence expand family claw crack dawn name salmon resource leg once curious'
```

This will re-import the same key pair that was originally generated, assuming you have the correct BIP39 passphrase. You can confirm this by checking that the public key is the one you expect.



## Installation

### Pre-requisites

You need a reasonably modern version of Java, specifically 21+. You can get this here:
- [Java JDK from Oracle](https://www.oracle.com/uk/java/technologies/downloads/)

### .jar file

You need the `convex.jar` file. See the main [Convex Readme](../README.md) for details.

At this point, assuming Java 21+ is correctly installed, you should simple be able to simply execute `java -jar convex.jar`  (appending any appropriate CLI arguments).

### Convenience Wrappers

It is helpful to have a script, alias or batch file which executes the Java command command for you so you can simply type `convex ....`

For Windows PowerShell, the following should work:

```
# Windows powershell - convex.ps1
function convex { 
  java -jar C:\path\to\convex.jar $args
}
```

Put this in your PowerShell profile (typically `Profile.ps1` in `$home/documents/PowerShell`) and the `convex` command should be available in all future PowerShell sessions.

For Linux and other Unix-like systems:

```
#!/bin/bash
java -jar path/to/convex.jar $@
```

Ensure the wrapper is somewhere in your `$PATH`, and you should be good to go!

## License

Copyright 2021-2024 The Convex Foundation and Contributors

Code in `convex-cli` is provided under the Convex Public License
