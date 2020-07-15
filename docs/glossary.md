# Convex Glossary

## Account

An Account is a record of identification and ownership within Convex. Accounts may be either:

* User Accounts: Accounts that are controlled by external users, where access is controlled by digital signatures on transactions.
* Actor Accounts: Accounts that are managed by an autonomous Actor, where behaviour is 100% deterministic according the the associated CVM code. 

## Actor

An autonomous entity implemented in CVM code on the Convex Network.

An Actor is defined with exactly one Account, but may send messages to and control assets managed by other Actors / Accounts.

## Address

An Address is a 20-byte value used to refer to Accounts. An Address is valid if it refers to an existing Account (User or Actor) in the CVM State.



## Belief

A Belief is a specialised data structure containing a Peer's combined view of what other Peers are communicating with respected to the Consensus Algorithm.

## Belief Merge Function

A specialised function that can be used to merge beliefs from different Peers.

Each Peer runs a Belief Merge function as part of the Consensus Algorithm. 

## Blob

A Data Object representing an arbitrary sequence of bytes.

## Block

A collection of transactions submitted simultaneously by a Peer.

Unlike Blockchains, a Block in Convex does *not* contain a hash of the previous block. Blocks can therefore be handled in parallel and re-ordered by the higher level Consensus Algorithm.

A Block must be digitally signed by the proposing Peer to be valid for inclusion in consensus.

## Consensus Algorithm

In general, a consensus algorithm is a procedure or protocol achieve agreement on a single data value among distributed processes or systems or the current state of a distributed system.

In the context of Convex, the Consensus Algorithm is the specific algorithm used to obtain consensus through the use of a convergent Belief Merge Function.

## Consensus Point

The greatest position in the Ordering of Blocks produced by the Consensus Algorithm which has been confirmed as being in Consensus. Each Peer maintains it's own view of the Consensus Point based on observed consensus proposals from other Peers.

The Consensus Point cannot be rolled back according to the rules of the Protocol (any attempt to do so would therefore constitute a Fork). However some Peers may advance their Consensus Point slightly before others.

Users transacting on the Convex network should use the Consensus Point of a trusted Peer to confirm that their transactions have been successfully executed on the Convex Network.

## Convex Network

A network of Peers, maintaining a consistent global state and executing state transitions according to the Consensus Algorithm and rules of the CVM.

## Convex Lisp

A programming language based on Lisp, that is available by default as part of the CVM.

All programming languages represent trade-offs. Convex Lisp prioritises features that are believed to be well suited to the development of decentralised economic systems. This includes:

* Emphasis on functional programming to reduce error and improve logical clarity
* Use of immutable data structures
* Actor-based model enabling trusted autonomous execution of code

## CRDT

Acronym for Conflict-free Replicated Data Type, a data structure that can be replicated across many computers in a network and is guaranteed (mathematically) to reach eventual consistency.

The Consensus Algorithm makes use of what is effectively a CRDT (of Beliefs) to guarantee convergence on a single consensus. 

## CVM

Acronym for Convex Virtual Machine. This is a general purpose computational environment that can be used to implement the state transitions triggered by transactions in the Convex Network.

## CVM Code

A representation of computer code that can be executed natively on the CVM. CVM code is based on a small number of core primitives that map to the Lambda Calculus, which can be composed in a tree data structure to represent arbitrary Turing-complete code.

Different languages may be compiled to CVM code.

## Data Object

A Convex Data Object is a first-class unit of information in the decentralised Convex system.

Data Objects include:

* Primitive values
* Data Structures representing composites of many values (including other data structure)

Most Data Objects are available within the CVM

## Environment

An Environment on the CVM is a mapping from Symbols to defined values. 

The Convex environment is should be familiar to those who study the formal semantics of programming languages. It is implemented as a functional, immutable map, where new definitions result in the creation and usage of a new Environment.

Each Account receives it's own independent Environment for programmatic usage. If the Account is an Actor, exported definitions in the environment define the behaviour of the Actor.

## Etch

The name of the underlying Convex storage subsystem - "A database for informtion that needs to be carved in stone".

Etch implements Converge Immutable Storage for Data Objects.

## Fork

A Fork in a consensus system is, in general, where two or more different groups diverge in agreement on the value of shared Global State.

Convex is designed to prevent Forks. In the unlikely event of a fork created by malicious actors or software / network failures, the Convex Network will follow the largest majority among known, trusted Peers (this is a Governance decision outside the scope of the Protocol).

## Function

A Function is a Data Object that represents a first-class function on the CVM. 

Functions may be passed as arguments to other functions, and invoked with arbitrary arguments. They may be anonymous, or given an name within an Environment. They may also be closures, i.e. capture lexical values from the point of creation.

Functions can support multiple arities on the CVM (e.g. `+`, although many functions only support a specific arity.

## Ordering

An Ordering defines the sequence in which Blocks of transactions are to be executed.

In normal use of the Convex system, the Ordering will be confirmed up to a certain point (the Consensus Point). Blocks after this point are not yet confirmed, but are in the process of being proposed for consensus.

## Peer

A Peer is a system that participates in the operation of the decentralised Convex Network.

Peers are required to use a private key (corresponding to the Peer's Account) to sign certain messages. Because of this, a Peer's Stake may be at risk if the system is not adequately secured.

## Schedule

The Schedule is a feature in the CVM enabling CVM code to be scheduled for future execution. Once included in the Schedule, such code is *unstoppable* - it's execution is guaranteed by the protocol.

Scheduled code may be used to implement actors that take periodic actions, smart contracts that have defined behaviour after a certain period of time etc.

## Smart Contract

A Smart Contract is a self-executing economic contract with the terms of the agreement written into lines of code that are executed deterministically on the CVM. Buyer and sellers can predict exactly how the Smart Contract will behave, and can therefore trust it to enforce contract terms and conditions effectively.

Typically a Smart Contract would be implemented using an Actor, but it is possible for a single Actor to manage many smart contracts, and likewise for a single Smart Contract to be executed across multiple Actors. It may be helpful to think of Smart Contracts as secure economic constructs, and Actors as an lower level implementation mechanism.

## Stake

A Stake is an asset with economic value put at risk by some entity in order to prove commitment to its participation in some economic transaction and / or good future behaviour.

Convex uses a mechanism called Delegated Proof of Stake to admit Peers for participation in the Consensus algorithm. Other forms of stakes may be used in Smart Contracts.

## Stake Weighted Voting

Convex uses Stakes to determine the voting weight of each Peer in the Consensus Algorithm.

Benefits for a Peer having a higher effective voting stake are:

* Slightly more influence over which Blocks get ordered first, if two blocks are simultaneously submitted for consensus 
* They may also benefit from slightly improved overall latency for Blocks that they submit. 

While good Peers are expected to be content neutral, they may legitimately wish to offer better QoS to their partners or customers, and having a higher voting stake can help them to achieve this.

The protocol does not allow Peers to reverse a confirmed consensus, or prevent (censor) a Block from being included in consensus. Their stake may be at risk if they attempt this.


## State

State refers to the complete information managed by execution on the CVM - the State is the value that must be agreed via the Consensus Algorithm

Where there is a risk of ambiguity, State may be termed "CVM State".

## State Transition Function

The State Transition Function is the function that updates the State in response to new Blocks of Transactions after they are confirmed by the Consensus algorithm.

Formally this might be recursively specified as 

```
S[n+1] = f(S[n],B[n])

where:
  f is the State Transition Function
  S[n] is the sate after n Blocks have been processed
  B[n] is the Block at position n in the cordering
  S[0] is the pre-defined initial State
```

## Transaction

An Transaction is an indivisible operation that can be executed on the Convex Network. A Transaction must be linked to a User Account.

Transactions must be digitally signed by the owner of the Account in order to be valid. 


