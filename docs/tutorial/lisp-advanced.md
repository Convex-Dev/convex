# Advanced Topics

If you've got this far, you may be interested in some of the more advanced features of Convex Lisp. This section is intended for people who want to know more about how Convex Lisp work, and how it integrated with the capabilities of the CVM.

## Complier Phases

How does 'Code as Data' actually work? The secret is in understanding the phases of the Convex Lisp compiler

### 1. Reading

Reading is the first phase that parses source code as text into CVM data structures (technically known as a *form*, since it is data that is structured to be used as code)

```clojure
"(foo :bar :baz)" -> '(foo :bar :baz)
```

The Convex Lisp parser is part of the Convex platform code but technically outside the scope of the CVM - there's no good reason for doing parsing on-chain when it can be performed easily and cheaply by clients. This means that you can't parse code from Strings on-chain, but this isn't a significant limitation: you can just parse off-chain and pass in the resulting data structure (skipping Phase 1)

### 2. Expansion

Expansion is the second phase of the compiler. Expansion takes the raw form data structures and translates them into Syntax Objects, which are representation of the Convex Lisp Abstract Syntax Tree (AST)

```clojure
'(foo :bar :baz) -> <Syntax Object>
```

In this phase, any macros are applied to the forms analysed, which has the effect of replacing them with the macro expansion. 

This means that arbitrary CVM code in macros *can* be executed during expansion - which in turn can be sometimes useful, e.g. in smart contract code that wishes to generate code based on analysing the CVM state.

Phase 2 expansion can be performed either on-chain or off-chain.

### 3. Compilation

In the third phase, Syntax Objects are *compiled* into *Ops*, which are the low level instructions that can be executed by the CVM.  

```clojure
<Syntax Object> -> <Op>
```

There are only a small number of Op types on the CVM, which are roughly based on the fundamental operations required to implement the [Lambda Calculus](https://en.wikipedia.org/wiki/Lambda_calculus). Currently these are:

- **Cond** - Performs a conditional branch
- **Constant** - Loads a constant value
- **Def** - Creates a definition in the environment
- **Do** - Executes a structured sequence of Ops sequentially
- **Invoke** - Executes a function with optional arguments
- **Lambda** - Creates a function
- **Let** - Defines a scope for local variables
- **Lookup** - Looks up a value from a definition in the environment

Ops can be nested, e.g. an Op of type **Do** may contain multiple child Ops. In this way, single Ops can be used to represent whole programs or algorithms.

Normally, users won't need to interact directly with Ops.

### 4. Execution

The final phase is execution, where Ops are executed in the CVM context. The Op may update the CVM state in various ways, and it may also return a result, so the process of Op execution can be informally viewed as a state update:

```
<Old CVM State> + <Op> => <New CVM State> + <Result>
```

Results from Op execution must be either:

- A valid CVM data object
- An exceptional result (e.g. an error or early return value)

Convex Ops are technically a form of [p-code](https://en.wikipedia.org/wiki/P-code_machine), analogous in many ways to Java bytecode. Using Ops gives a few big advantages:

- Ops can be executed very efficiently many times (avoiding the more expensive phases of parsing, expansion and compilation).
- Ops are very compact in terms of memory used - making them ideal for network transmission and efficient usage of on-chain storage.
- We can improve underlying performance and implementation details of the CVM without breaking CVM code that has been compiled to Ops.
- Ops are designed to match up with the runtime and security checks that the CVM must perform when executing code securely on-chain. 

## Macros

We've actually used a couple of macros already in this guide: `if`, `undef` and `defn` are all examples of macros. 

A macro is a procedure that generates new code at compile time (technically, in the *expansion* phase of the compiler). Macros are an incredibly powerful tool that allow you to enhance the Convex Lisp language with new capabilities and syntax.

As a simple example, let's consider a macro that allows you to use 'infix' notation for for mathematical expressions, i.e. instead of writing `(+ 1 2)` we want to write `1 + 2`. It is possible to do this with a simple macro that rewrites the infix expression into the expected Lisp format:

```clojure
(defmacro infix [arg1 operator arg2]
  (list operator arg1 arg2))

(infix 1 + 3)
=> 4
```

What is happening here? The macro defines an expander function that takes three arguments `[arg1 operator arg2]` and then outputs a list starting with the operator. This transforms `1 + 3` into the list `(+ 1 3)` which can then be executed normally. We can see the effect of macro expansion by using the `expand` function, which performs the expansion of a form without evaluating it:

```clojure
(expand '(infix 1 + 3))
=> (+ 1 3)
```

Macros are powerful tools, but should only be used when they are needed - they are more complicated to use and understand than regular functions. The best use cases for macros are usually:

- Writing new syntax / language extensions that need to make use of arguments *without* evaluating them beforehand. If you are happy to use arguments after regular evaluation, then regular functions are probably a better fit.
- Situations where you want code to be evaluated at compile time, e.g. to avoid repeatedly performing the same expensive computation at runtime.

## Upgradable Actors

A key risk of developing smart contracts is that once they are live, significant losses may occur if bugs are found. Losses could be from theft by malicious actors that manage to exploit a security weakness, or a bug that causes assets to be permanently lost.

It is therefore *an option* to make Actors upgradable. This is no panacea but a trade-off: You can the ability to patch problems in the original smart contract, but also open up the possibility that this upgrade feature itself may be exploited by attackers.

An upgradable actor can be implemented by providing an exeported function that allows a trusted user to execute an `eval` operation in the context of the Actor. A minimal implementation of this that lways trusts the user who deployed the Actor might look like:

```clojure
;; deploy an upgradable Actor
(def upgradable-actor
  (deploy 
    '(do
       (def owner *caller*)
       
       (defn upgrade [code]
          (assert (= *caller* owner))
          (eval code))
          
       (export upgrade))))
       
;; Check we are the owner!
(= *address* (call upgradable-actor (upgrade 'owner)))
=> true       

;; Add a new function to the Actor and export it
(call upgradable-actor 
  (upgrade
    '(do
        (defn foo [x] :foo-called)
        (export foo))))
        
;; Call the new function
(call upgradable-actor (foo 12))
=> :foo-called     
```

**SECURITY NOTE:** Adding a general purpose upgrade feature like this lets you correct bugs or add new enhancements to Actors, but it opens the risk that the same mechanism could be used to compromise the Actor's behaviour if an attacker were able to impersonate the owner, and also creates the risk that the Actor may be permananently disabled by the owner by mistake. As always, you must perform your own security analysis to determine whether this trade-off is worthwhile for the Actors that you deploy.


