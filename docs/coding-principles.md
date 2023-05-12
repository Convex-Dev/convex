# Coding principles

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

### Input validation on `:callable?` functions

Any `:callable?` function should perform input validation, since it may be called by any other user or actor on the network and hence is the point at which security checks should be performed. 

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
(defn ^:callable? do-something [id]
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

## Design principles

### Immutable first

Everything is an immutable data structure, with the exception of necessary
mutable values for either:
1. Locally managed state
2. Lazy computation / caching

### Trust the JVM

We unashamedly exploit the JVM as an excellent runtime platform for decentralised systems. 
It's very good at what it does, in particular the following attributes are very useful:

- Efficient GC of short-lived objects. Much cheaper than C++ heap allocations, in fact.
- Fast JIT compiler. Close enough to C++ that we don't care.
- Rich runtime library. We don't need many external dependencies, which add complexity
and present security risks.
- Memory safety. No buffer overflows to worry about.
- Portability. This makes it easy to deploy pretty much anywhere.

The use of a memory-managed runtime is of particular importance to this project.
We absolutely require top class garbage collection to clear unnecessary data from memory
while also ensuring that we can exploit structural sharing of persistent data structures.
We also need the exploit soft references for lazy loading of data structures
that can be evicted when no longer required. Alternative means of managing this 
(reference counting etc.) were judged infeasible for performance and complexity reasons.

### Canonical format

Our data representations make use of a single, canonical data format. Advantages:

- Sorting order guaranteed and stable
- Better caching / de-duplication with hashes
- Identity comparison == hash

### Defensive coding

Assume that you are being passed bad / malicious inputs. Check everything, 
especially if there is any chance that it may have come from an external system.

### Fail Fast

Stop the current operation as soon as any unexpected error occurs. Throw an exception so
that a higher level operation can determine what step to take.

### Common sense

Any of the above principles can be overridden by reason, evidence and common sense.

"The three great essentials to achieve anything worthwhile are, first, hard work; second, stick-to-itiveness; third, common sense."
― Thomas Edison


## Some Inspirations

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
