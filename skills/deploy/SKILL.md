---
name: deploy
description: Deploy an actor (smart contract) to the Convex network. Use when the user wants to create a new on-chain actor with exported functions.
argument-hint: "<description-of-actor>"
---

# Deploy a Convex Actor

Actors are autonomous on-chain programs with their own address, state, and exported functions.

## Actor Structure

A typical actor deployment:

```clojure
(deploy
  '(do
     ;; Internal state
     (def counter 0)

     ;; Exported functions (callable by others)
     (defn increment []
       (def counter (+ counter 1))
       counter)

     (defn get-count []
       counter)

     ;; Export public API
     (export increment get-count)))
```

## Key Rules

- `deploy` returns the new actor's address (e.g. `#12345`)
- Only `export`ed functions are callable from outside
- Internal `def`s are private state
- Actors have their own `*address*` and `*balance*`
- Use `(set-controller #ADDR)` inside the actor to set who can upgrade it

## After Deployment

1. Note the returned address for the user
2. Optionally register a CNS name: `(call #9 (cns-update 'my.actor.name *address*))`
3. Test by calling an exported function: `(call #NEW-ADDR (get-count))`

## Workflow

1. Help the user design the actor based on `$ARGUMENTS`
2. Write the Convex Lisp source
3. Deploy using `transact` with the `(deploy ...)` expression
4. Verify the deployment with a test query
