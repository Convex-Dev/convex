# Coding principles

This document contains principles and considerations for coding on Convex. The target audience is dApp / smart contract developers and those who wish to contribute to core Convex technology.

## Architecture

### Prefer pure dApps

An ideal Web 3.0 application model is a "pure" dApp that is implemented as a client side front-end and uses only decentralised services such as Convex as a back-end.

This has the following advantages:
- Supports fully self-sovereign users
- Avoids the requirement to maintain Web 2.0 server backends

As such, it is recommended that Convex ecosystem applications adopt a pure dApp model if possible.

Of course, this may not always be possible. Reasons to include a traditional Web 2.0 back-end include:
- Integration with other back-end services
- Efficient large back-end data storage and analytics
- Custodial solutions, where private keys and assets are managed on behalf of used in a centralised fashions
- Private / sensitive data that is not suitable for a public decentralised network

### Don't roll your own!

Many application smart contract needs are provided "out of the box" on Convex, and in many cases quite sophisticated dApps can be developed **without writing any smart contract code or deploying any new actors**. This is obviously preferable to avoid development costs and risks if requirements can be met by existing on-chain services.

Such facilities include:
- General purpose fungible tokens e.g. `convex.multi-token`
- Automated market maker / currency exchange - `torus.exchange`
- W3C compatible Decentralised Identity registry - `convex.did`
- Various NFT implementations
- Trust monitors for authorisation and governance: `convex.trust` and related libraries

## Smart Contract Security

This section contains tips and considerations for smart contract security if you are developing Actors on Convex. They are not absolute rules nor do they guarantee complete security, however our aim is to make developers aware of common security risks and pitfalls and how to addess them.

### Prefer `*caller*` over `*origin*`

In smart contract code, you almost always want to handle a request assuming the `*caller*` is the one attempting the transaction. This is because `*caller*` was the account making the decision to call the smart contract, and therefore any authorisation checks should be performed on the basis that this account authorised the execution of this part of the transaction.

If you check `*origin*` instead, there is a serious risk that an attacker may trick the user into executing some smart contract code, which then allows the attacker to execute your smart contract code while impersonating `*origin*`

### Use `query` to avoid re-entrancy risks

In some cases, smart contracts may be vulnerable to re-entrancy attacks. This occurs when code calls potentially untrusted, which then calls back into the original smart contract (which could be in an inconsistent state in the middle of execution).

To avoid this risk, it is often helpful to use the `(query ...)` for which discards and state changes made by the enclosed form. This works whenever the caller simply wants the result of the enclosed expression(s), and does not expect or require any state changes to occur.

This mitigates re-entrancy risks because even if untrusted code launches a re-entrancy attack, any side effects of that attack will be discarded. For example, a call:

```clojure
(call untrusted-actor (foo))
```

Should be replaced with:

```
(query (call untrusted-actor (foo)))
```

### Input validation on `:callable` functions

Any `:callable` function should perform input validation, since it may be called by any other user or actor on the network and hence is the point at which security checks should be performed. 

Specific recommendations are as follows:

#### Check authorisation of `*caller*`

Often it is necessary to check that the caller has sufficient authorisation to perform the request. It is recommended to set up a trust monitor to perform this validation, which provide flexibility to change or manage authorisation methods. In the checking code, perform it like this:

```clojure
;; In environment setup
(import convex.trust :as trust)

;; In checking code
(trust/trusted? trust-monitor *caller* :optional-action-id)
```

#### Check IDs are valid

If any identifiers are passed that refer to digital assets or other entities, input validation should check that the target entity exists. 

Normally, this can be done by checking whether the ID is a valid key in a map or other indexed data structure. A typical pattern is:

```clojure
(defn ^:callable do-something [id]
  (let [entity (get map-of-entities id)]
  	;; Check if entity was successfully found in map
  	(or entity (fail "Invalid ID"))
  
    ;; other validation
    (do-internal-stuff entity)))
```

#### Check numeric types

If a parameter is expected to contain a numeric value, care should be taken to ensure that the value if of an appropriate type and of an expected range. 

A common pattern is to perform a cast, which will fail if the value if not of a valid type and will guarantee conversion to the correct type if the cast succeeds. It is generally OK to re-bind the cast value in a `let` form, e.g.

```clojure
(let [value (integer value)]
  ....)
```

Typical other checks include:
- Non-negative values `(<= 0 value)` 
- Range checks `(<= minimum value maximum)`
- Specific numeric type checks e.g. `(double? value)`

#### Enforce data constraints

If a specific data structure is expected as an argument, it is important to check that this data has the right "shape" or values within it. This is especially important if the data will be stored for future use: you do not want a situation where an attacker can corrupt data structures with invalid information.

Typical checks might include:
- Checking the length of a `Vector`
- Checking for the presence of specific `Map` keys
- Checking constraints on particular values within the data structure (e.g. must be a valid ID)

### Do external calls last

When performing an external `call` to potentially untrusted code, it is important to avoid re-entrancy risks or other situations where the external call may make unexpected state changes that disrupt execution.

A recommended sequence for actor code in a callable function is as follows:
- **Validate** any inputs
- **Compute** changes required (`query` can be used to make external calls safe during this stage)
- **Perform** external `call`(s). if there are multiple external calls, extra care may be necessary to ensure assumptions remain valid for following calls
- **Return** any result values

### Avoid code injection risks

Convex provides a powerful execution platform capable of executing arbitrary Turing complete code and supports first class functions. A significant risk occurs when an attacker may be able to get a user or actor to execute arbitrary code within their own account (and thus enables the attacker to impersonate the target, e.g. stealing digital assets).

In light of this risk, the following are very important:
- DO NOT directly apply a function that may have come from an untrusted source (e.g. as a parameter to a callable function). `(untrusted-function arg1 arg2)` is a code injection risk whenever an attacker might be able to provide `untrusted-function` to your code
- DO NOT use `eval` on data which may come from an untrusted source. This is another form of potential code injection
- If you must execute code from a potentially untrusted source, but don't expect this code to have any side effects, then consider wrapping untrusted code execution in `query`. This will discard any state changes and only provides the function result, which is generally safe.
- If you do any form of dynamic code execution with `eval` or higher order function parameters, you should consider executing such code in an isolated account (not controlling any economic assets) to limit the potential downside risks. 

## Efficiency

Convex provides a powerful substrate for global, decentralised economic systems. If your code is going to be used by a large user base, efficiency becomes increasingly important. Here are some considerations for efficient code:

### Minimise on-chain work

No matter how efficient we make Convex, off-chain computation and storage will always be cheaper.

As a general design rule, your application and smart contracts should minimise on-chain code and data to what is strictly necessary.

Good candidates for being on-chain:
- Economic transactions
- Ownership and control of digital assets
- Data relating to trust and authorisation
- Hashes allowing authentication / provenance of off-chain data 
- Metadata intended for public consumption

Bad candidates for being on-chain:
- Large files or content blobs (should be in off-chain storage or data lattice)
- User interface data (should be managed in front-end)
- Textual information for human consumption (possible privacy risks and/or should be handled in front-end e.g. for internationalisation)
- Secondary data structures used for analytics, e.g. indexes
- Logs of historical data

### Minimise separate transaction steps

In general, it is much cheaper to do multiple things in a single transaction rather than split things into multiple transactions.

If it is possible, in the context of some application, to perform multiple steps at once then a facility such as a library function can be provided to enable this.

Alternatively, multi-transactions can be used to combine multiple transactions into one, which also benefits from lower overall cost.  

### Use efficient data types

Memory usage of transactions and smart contracts can be minimised by use of appropriate data types.

Some tips:
- `nil`, `true`, `false` and the integer `0` require only 1 byte - where possible use these
- A `Set` is more memory efficient than a `Map` if you only need keys and don't care about values
- Use Integer IDs allocated via an monotonically incrementing counter where possible. This is memory efficient and guarantees avoidance of collisions.
- Don't store arbitrary user-provided data

### Avoid `O(n)` operations

Most Convex operations are very cheap, typically `O(log n)` or `O(1)` cost. This can scale well to large data sets. However `O(n)` operations like scanning every element of a vector increasingly become very expensive as data volumes increase.

At some point, juice costs of `O(n)` operations may become high enough that these operations on the given data structure cannot even be executed!

Most actor code should **never** perform `O(n)` operations. If you have any of these, consider alternatives such as:
- Replace with `O(1)` operations, which is usually possible
- Moving `O(n)` work off chain, where analysing large data structures can be done cheaply via multiple methods
- Breaking the work up into smaller steps
- Enforcing strict limits on `n` (e.g. a maximum of 10 items in a list)

### Cleanup irrelevant data

In many cases, data can be deleted if no longer relevant. Look out for cases where you can simple delete data when it is updated in a transaction.

For example, if you have a `Map` containing account balances then it might make sense to remove a map entry entirely rather than setting the balance to `0` for a specific key (presumably lack of an entry would be interpreted as a zero balance in any case).

This also creates a positive economic incentive for users to perform transactions that trigger this cleanup (e.g. getting rid of small balances) since they will benefit from the memory refund.

### 3rd Party Cleanup

In some cases, cleanup operations may be expensive to compute directly, e.g. identifying records in a large data structure that are expired because of old timestamps could require `O(n)` computation. However the cleanup itself may be cheap to perform, e.g. `O(1)` cost to remove an expired entry.

A simple solution to this problem is to allow any 3rd party to compute the cleanups off-chain, and execute a special cleanup `:callable` function to dispose of the redundant data. 

Economically, the 3rd party benefits from getting the memory refund from disposed data, which is likely to be more valuable than the execution cost of the cleanup. (especially if multiple cleanups can be executed in a single transactions).

### Pre-compile code

While Convex provides an on-chain compiler that can compile and evaluate code provided in transaction, there is no point paying for compilation if you don't need to!

Pre-compiling transaction code is likely to result in a worthwhile cost saving, especially if you execute many similar transactions. This is especially true if your transaction code includes macros, that may expand to much larger bodies of code with significant compilation costs.

You can also reduce execution costs by statically linking to referred accounts like `#1234/foo` rather than performing dynamic lookups. This is a good strategy as you if you are sure that you are referring to a fixed target address.

## Convex Core Technology Implementation

### Immutable first

Everything is an immutable data structure. 

Convex can be considered as a pure, functional system where pure functions produce new pure immutable data structures - most importantly the updated global state. 

Internally, there are some mutable values used for either locally managed state, lazy computation or efficient caching. These however are not visible to CVM code, and should be considered purely as internal optimisations.

### Exploit the JVM

We unashamedly exploit the benefits of JVM as an excellent runtime platform for decentralised systems. It's very good at what it does, in particular the following attributes are very useful:

- Efficient GC of short-lived objects. Often much cheaper than C++ heap allocations.
- Fast JIT compiler. For well written code, performance is close enough to C++ that it is never a significant concern.
- Rich runtime library. We don't need many external dependencies, which add complexity to the core implementation and present security risks.
- Memory safety. No buffer overflows to worry about.
- Portability. This makes it easy to deploy pretty much anywhere.

The use of a memory-managed runtime is of particular importance to this project.
We absolutely require top class garbage collection to clear unnecessary data from memory while also ensuring that we can exploit structural sharing of persistent immutable data structures. Without garbage collection, such structural sharing can become complex and expensive.

We also need the exploit soft references for lazy loading of data structures
that can be evicted when no longer required. Alternative means of managing this 
(reference counting etc.) were judged infeasible for performance and complexity reasons.

### Canonical Encoding format

Our data representations make use of a single, canonical data encoding format. Advantages:

- Sorting order guaranteed and stable
- Better caching / de-duplication with hashes
- Identity comparison == hash equality

### Defensive coding

Assume that you are being passed bad / malicious inputs. Check everything, 
especially if there is any chance that it may have come from an external system.

### Fail Fast

Stop the current operation as soon as any unexpected error occurs. Throw an exception so that a higher level operation can determine what step to take.

### Common sense

Any of the above principles can be overridden by reason, evidence and common sense.

"The three great essentials to achieve anything worthwhile are, first, hard work; second, stick-to-itiveness; third, common sense."
― Thomas Edison


### Some Inspirations

- *Haskell* - for its functional purity, and attribute which is extremely valuable for
decentralised systems.

- *Lisp* - for demonstrating the power of homoiconicity, and the ability to bootstrap a
language ecosystem with just a few core primitives closely linked to the Lambda Calculus.

- *Clojure* - primarily for its syntax and functional style, an elegant evolution of Lisp
for the modern age.

- *Persistent data structures* - functional data structures that enable efficient operations
such as update while preserving previous copies of data in an immutable fashion.

- *Java* - for giving us the JVM, an unusually robust and high-performance platform
for implementing systems of this nature. 

- *Ethereum* - for demonstrating a working decentralised execution engine.

- *Bitcoin* - for demonstrating decentralised consensus, albeit in a very inefficient fashion 
