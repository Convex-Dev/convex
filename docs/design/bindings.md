## Let and local bindings

### Overview

Convex Lisp (and the CVM in general) supports lexically bound values, which are referred to as local bindings because they are visible only locally to the currently executing block of code.

### Binding expressions

Binding expressions are blocks of code that establish a set of local lexical bindings. There are may such binding forms, and new ones can be defined (e.g. using macros). The most common such forms are described below

#### let binding

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

#### function binding

A function counts as a binding expression whenever it is called - in which case the arguments passed to the function are bound to the function parameters for the scope of function execution.

```clojure
(defn square [x] (* x x))

(square 4)
;; => 16
```

In this simple example, the argument value `4` is bound to the local symbol `x` for the duration of the function body execution.

#### loop / recur

The `loop` form is a binding expression which behaves like `let` in most circumstances, except it allows the use of a special `recur` form inside that causes the `loop` to be re-run with new values for the loop variables.

A simple example using an accumulator to implement a factorial computation:

```clojure
(defn fact [x]
  (loop [i x, acc 1]
    (if (<= i 1)
      acc
      (recur (dec i) (* acc i)))))
      
(fact 5)
;; => 120
```

In each loop iteration, the index value `i` is checked to see if the loop has finished. If yes, the accumulated value is returned. If not, a `recur` expression causes the loop to repeat after decrementing the loop index and multiplying the accumulator by `i`. This pattern (a loop containing a conditional which either returns a value or recurs) is a common way of implementing repetitive loops in Convex Lisp.

Note: the `recur` form may also be used for tail-recursion within a function.

#### macro binding

Macros are similar to functions in that they have a binding vector of macro parameters. The key difference is that the macro is passed the *unevaluated forms* as the macro arguments rather than the result of the evaluating these expressions.

```
(defmacro check [condition reaction]
  '(if (not ~condition) ~reaction))
  
(check (= (+ 2 2) 4) (fail "Laws of arithmetic violated"))
;; => nil
```

Note that

### Special binding features

As well as allowing symbols to be bound to single expressions, there are a number of additional binding features that help to express certain kinds of 

#### Ignored bindings ( _ )

The Symbol `_` (underscore) has a special meaning when used as a binding symbol: It computes the binding value as normal but throws away the result without updating the local bindings.

```clojure
(let [_ (do-something)]
   ;; body executes with no new value bound
   )
```

This feature is useful if:

- You want to execute an expression in a binding form (presumably for a side effect) but don't care about the result.
- You want to communicate that the value of some input arguments aren't important and should be ignored e.g `(defn f [_ _] ...)`

#### Binding vectors ( [a b c] )

A vector can be used in place of a Symbol for a binding, in which case the corresponding expression is treated as a sequence and bound recursively to the elements of the binding vector.

Example:


```clojure
(let [point [2 3]
      [x y] point]
   (* x y))
   
;; => 6
```


#### Variable arity bindings ( & )

A single ampersand `&` may be used in a binding expression to indicate that the following symbol should zero or more arguments, which will be represented as a `Vector` value. This is convenient for destructuring argument lists to a function which accepts variable arity arguments.

Example usage:

```clojure
(defn following-args [a & more] 
  more)
  
(following-args 1 2 3 4) 
;; => [2 3 4]
```

#### Setting local bindings ( set! )

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

`set!` can be useful if you want to code in a mutable, imperative programming style. In this case, you can treat local bindings in a similar way to how you might use local variables in object-oriented languages. Here is the factorial example re-written in a more imperative style:

```clojure
(defn fact [x]
  (set! acc 1) ;; we need to set an initial value for the accumulator before the loop
  (loop []
    (set! acc (* acc x))
    (set! x (dec x))
    (if (> x 1) (recur))
    acc))

(fact 5)
;; => 120
```

This technique is occasionally useful, but in general should be avoided because it makes code harder to reason about - in effect you are mutating the local bindings in the current execution context. Normally we recommend preferring functional constructs instead: (a `loop` with `recur` used for re-binding, `let`, `fn` etc.)



### CVM implementation

#### Data representation 

Under the hood, local bindings are represented in the CVM as a map of bound symbols to values. This map is saved at the start of each binding expression, and restored when the binding expression execution is complete.

The map itself is an immutable data structure - 

This design has a few technical consequences worth noting:

- It enables Closures (functions wrapping local bindings) to be efficiently constructed with a reference to the local bindings map.
- Unlike dynamic (`def`) bindings, local bindings have no associated Syntax object or metadata during execution. This gives local bindings a performance advantage, and hence we can encourage their usage by providing a lower juice cost.
- Any changes to local bindings will result in a new map being produced, however this is comparatively cheap on the CVM thanks to structural sharing. Also, because local bindings are highly transient such data is unlikely to every require persistent storage and will be quickly cleaned up by the garbage collector after use.




