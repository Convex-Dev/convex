## Def and the dynamic environment

### Defining values in the environment

The `def` special form can be used to create bindings for a symbol in the current environment. Once defined, such symbols can be referred in subsequently executed expressions, in which case they will evaluate to the currently bound value.

```clojure
(def foo 10)

(* foo 7)
;; => 70
```

### Accounts and environments

Every Account in Convex gets it's own dynamic environment. As a result, defining a binding with `def` affects the environment of the Account currently executing (which may be either a User or Actor account).

```clojure
;; ============
;; In account A
(def foo 13)
foo
;; => 13 

;; ============
;; In Account B
foo
;; => UNDECLARED error
```

This is a good and important property: Accounts should never have the ability to directly interfere with other Accounts, so in should not be possible to define something in another Account's environment.

Of course, sometimes you need to pass information between Accounts, so Convex provides a number of facilities for doing this:

- You can (read-only) access another environment via an alias library using `import`. This allows access to definitions in another environment using a Symbol of the form `alias/symbol-name`
- Actors can update their own environment when an exported function is `call`ed by another User / Actor. The `call` operation switches the security context, and hence the environment, for the duration of the `call` so that an Actor can update it's own environment (and importantly *cannot* update the environment of the `*caller*`)
- You can read values from arbitrary environments off-chain, and feed this the results of processing this information back in via new transactions. This is usually recommended if you want to do computationally heavy processing of data.

### Libraries and aliases

Good programming practice suggests the important of structuring programs in modular units that are relatively self-contained. Most programming languages have a concept of "libraries" that can be included and re-used by multiple programmes

Convex provides an easy way to implement libraries that takes advantage of the dynamic environment model:

- Each library receives its own Account and hence it's own Environment for library definitions
- Libraries can be deployed using the `deploy` capability, just like any other Actors
- Other Accounts may `import` definitions for their own use

A minimal example:

```clojure
;; Deploy a library with a single function definition
(def my-lib (deploy '(defn foo [] 13)))

;; Import the library for the current use with the alias 'ml'
(import my-lib :as ml)

(ml/foo)
;; => 13
```

The complete set of aliases for the current environment is stored in the binding `*aliases*`, which is a map of Symbols to Addresses for the aliased Accounts. The `*aliases*` also includes a `:default` alias which is used for unqualified Symbols and is initially set to refer to the Convex core library  - however this can be overriden if required.

IMPORTANT SECURITY POINT: Library code invoked as a normal function executes in the same security context as the calling code (i.e. `*address*` for the current Account does not change). This is useful because it means that library code can act on the callers behalf (e.g. transferring tokens, calling other Actors) but also presents some risks. Make sure that you trust library code that you call!

### Symbolic evaluation

When a Symbol is encountered while executing CVM code, a Lookup operation is performed.

The lookup attempts to resolve the symbol in the following order:

1. The local lexical binding 
2. The current dynamic environment for *address*
3. If the Symbol is qualified e.g. `alias/foo`, and a valid alias exists, the dynamic environment for the aliased Account is checked
4. If the Symbol is unqualified e.g. `count`, the dynamic environment for the `:default` alias is checked (usually the Convex core library)
5. If the Symbol is a special symbol e.g. `*balance*`, the special symbol value is returned
6. If all else fails, an UNDECLARED error condition is thrown

### Security Considerations

Some important points:

- Each Account has its own environment
- The environment of an Account can *only* be changed by code executing in the security context of the Account, i.e. with the correct `*address*` set in the currently executing code.
- By the account security model, the environment therefore can only be updated by:
  - A valid signed transaction submitted by a User for a User Account
  - Actor Code executed via a `call` operation for an Actor account
- An Actor which has no operations exported via its `*exports*` map can therefore *never* have its environment updated. This security property can be used to create immutable Actors (e.g. non-updatable library code)

Key threats and mitigations are outlined below.

#### Code injection attacks

If you don't completely trust the values in an environment that you don't control (whether a User or Actor Account), then be *extremely* careful about using values from such environments as functions. For example, the following snippet of Actor code is subject to significant code injection risks:

```clojure
(import some-dubious-library :as badlib)

(defn unsafe-contract [foo]
  (badlib/some-function))

(export unsafe-contract)
```

Why is this dangerous?

- Anyone can call into this contract using `(call ... (unsafe-contract))`
- `unsafe-contract` calls the function `badlib\some-function` directly, i.e. in the Actor's own security context
- `badlib\some-function` will be dynamically looked up and executed any time the `unsafe-contract` function is called, which is externally exported and therefore trivial for malicious actors to `call` at any time.
- Anyone who is able to redefine `some-function` in `badlib` can therefore use this to execute arbitrary code in this Actor's security context (draining funds, rewriting data etc.)

Recommendations:

1. Never invoke functions directly looked up from environments that you don't trust. Even if the function is *currently* safe, it might be changed in the future to something unsafe. Only invoke functions from environements that you trust completely, or which provably cannot be updated.
2. If you do look up values from an environment that you don't trust, treat these as *untrusted user input* and validate appropriately
3. Double-check the usage and security implications of all qualified Symbols that might refer to library code.


#### Mutable libraries

It is possible to `import` any Account as a library. This opens up the possibility of dynamically using Libraries that are mutable.

There are some legitimate reasons why you might want to do this:

- Having the ability to securely *update or upgrade* a library, which might otherwise require the use of techniques such as proxy functions (which add overhead as well as having their own set of risks)
- Having an emergency capability to deploy a bug fix
- Having some features of the library that need to incorporate new data over time (e.g. new currency codes)

There is, of course, significant risk in doing this. Aside from the risk of malicious attacks from code injection, mutable libraries may cause difficult to fix bugs if the changes invalidate assumptions in your own code. e.g. deleting a definition from a library is likely to case code that uses it to fail with UNDECLARED errors.

1. Prefer immutable libraries wherever possible. Immutable libraries are Actors with no `*exports*`, and as such no code can be legally executed on the CVM that changes the library's environment after it has been deployed.
2. Avoid using a mutable library unless you trust it completely.
3. If you use mutable libraries, make sure that you understand precisely the ways in which the library can change, and design your code that calls it accordingly
4. If you are designing a library that needs to be mutable, consider minimising the number of ways that the library can change (and if possible, committing to this by limiting change to a small set of well-defined update functions that can be verified.
5. Minimise calling mutable libraries, even if trusted, from Accounts that has access to high-value assets.

