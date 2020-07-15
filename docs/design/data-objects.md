# Decentralised Data Objects

## Overview

A Decentralised Data Object (DO) is the fundamental unit of information used in Convex. 

## DO Types

A DO is either:

* An embedded primitive, including:
  *	Integers
  *	Booleans
  * Short strings
  *	Keywords
  *	Symbols
* A Cell (a compound data container which may contain smart references to other DOs)

A Cell can be:

* A data structure (maps, lists, vectors, sets)
* A large primitive (blobs, long strings)
* A built-in record object type (Block, Chain, State, Syntax records etc.)
* A sub-structure of one of the above

A DO is Canonical if and only if it is valid as a first class programmatic value in the CVM. Most DOs are canonical, but some are not (e.g. sub-nodes of a large tree data structure are not valid data structures in their own right).

## Encoding

Every unique DO has an encoding, which is a representation of the DO’s complete value as a string of bytes. 
Encodings start with a single Tag Byte which identifies the type of the DO. This is followed by a type specific representation with the following properties:

* It is designed to be read in sequentially, allowing for efficient decoding from byte streams
* It has a bounded maximum size (currently 8191 bytes) that ensures it will fit easily within appropriate buffers and helps ensure bounds on memory usage.
* It can contain other embedded DOs, or a smart reference (see below) to other cells.

## Object ID

Every DO has an associated Object ID, which is the cryptographic hash of its encoding. This can be used to uniquely identify the DO in storage, or via a smart reference.
Smart References

A smart reference (Ref) is a reference to a DO via its Object ID. A smart reference may be resolved to obtain the actual instance of the DO.

In the Convex implementation, smart references can exist in one of two states:

* A Direct Ref, which refers to a DO instance stored in memory.
* A Soft Ref, which contains an Object ID referring to the DO but may not have an actual DO instance (e.g. because the DO was garbage collected, or because the underlying data encoding has not yet been received from a remote Peer)

A Direct Ref can always be resolved successfully, however an attempt to resolve a Soft Ref may fail. In such cases, the implementation will need to find an alternate solution, typically attempting one or more of the following:

* Obtaining a copy of the DO from storage (indexed by Object ID)
* Requesting a copy of the DO from a remote Peer
* Signalling failure to the user

During operation of the consensus algorithm, DOs communicated by Peers are fully validated by receiving Peers before acting upon them. If a Peer fails to provide the complete, fully resolvable tree of DOs as required by the protocol for any reason then it will be ignored / excluded by other Peers.

## Merkle Tree Properties

In can be observed that:

* Every DO has a data encoding
* Every Cell has an encoding that includes smart references
* Every smart reference contains the Object ID

As such, it should be clear that any DO is effectively a Merkle Tree. This allows for efficient and secure verification of DOs, as well as other useful properties of Merkle Trees.

A particularly important property is equality detection – because DOs have a unique encoding, and because each unique encoding has a unique cryptographic hash (at least as far as the extremely improbable chance of collisions), having the same Object ID means that two DOs are equal in value. This enables large data structures to be very efficiently checked for equality.


