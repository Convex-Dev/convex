## Let and local bindings

### Overview

Convex Lisp (and the CVM in general) supports lexically bound values, which are referred to as local bindings because they are visible only locally to the currently executing block of code.

### Binding expressions

The simplest way to establish a local binding is to use the `let` special form.

```clojure
(let [x 100 y 200]
  ;; Code body goes here using x and y
  (+ x y)
  )
  
;; x and y now no longer bound
```

Within the let block, the symbols `x` and `y` are bound to the values `100` and `200` respectively, and these bindings are valid for any code contained within the `let`	 body.

Other types of expression also establish bindings in a similar way.

### Special binding features

As well as allowing symbols to be bound to single expressions, there are a number of additional binding features that help to express certain kinds of 

#### Ignoring bindings

The Symbol `_` (underscore) has a special meaning as a binding symbol: It computes the binding value as normal but throws away the result without updating the local bindings.

```clojure
(let [_ (do-something)]
   ;; body executes with no new value bound
   )
```

This feature is useful if:

- You want to execute an expression in a binding form (presumably for a side effect) but don't care about the result.
- You want to communicate that the value of some input arguments aren't important and should be ignored e.g `(defn f [_ _] ...)`

#### Binding vectors

A vector can be used in place of a Symbol for a binding, in which case the corresponding expression is treated as a sequence and bound recursively to the elements of the binding vector.

Example:


```clojure
(let [point [2 3]
      [x y] point]
   (* x y))
   
;; => 6
```


#### Variable arity bindings

A single ampersand `&` may be used in a binding expression to indicate that the following symbol should zero or more arguments, which will be represented as a `Vector` value. This is convenient for destructuring argument lists to a function which accepts variable arity arguments.

Example usage:

```clojure
(defn following-args [a & more] 
  more)
  
(following-args 1 2 3 4) 
;; => [2 3 4]
```

#### Setting local bindings

It is possible to overwrite a local binding in the body of a binding expression using `set!`:

```clojure
(let [a 10]
  (set! a 20)
  a)
  
;; => 20
```

It is not necessary for the value to be bound already: `set!` will create a new binding in the current local bindings if one does not currently exist. `set!` is functionally equivalent to adding an additional `let` binding to the immediate surrounding binding form at the current execution position.

Note that the newly set binding is still local and only visible up to the end of the current binding form (in this case, the end of the `let` body). The following code would produce an error, since the local binding introduced is out of scope:

```clojure
(do
  (let [a 10]
    (set! a 20))
  a)
  
;; => UNDECLARED : a
```

This technique is occasionally useful if you want to develop some functionality in a mutable, imperative programming style. But in general should be avoided because it makes code harder to reason about - in effect you are mutating the local bindings in the current execution context. Normally we recommend preferring functional constructs instead: (`loop`, `recur`, `let`, `fn` etc.)



### CVM implementation

Under the hood, local bindings are represented in the CVM as a map of bound symbols to values.

This design has a few consequences worth noting:

- It enables Closures (functions wrapping local bindings) to be efficiently constructed with a reference to the local bindings map.
- Unlike dynamic (def) bindings, local bindings have no associated Syntax object or metadata during execution. This gives local bindings a slight performance advantage

