# Convex Lisp Guided Tutorial

This guide is intended for developers interested in learning about Convex Lisp. We will take you through the basics of the language, all the way through to designing and deploying a simple smart contract!

## Setup

Using the [Sandbox](https://convex.world/#/sandbox) is the easiest way to experience Convex Lisp. We recommend that you try it out as you go through this guide: It's more fun to get instant feedback and try out new ideas quickly! To do this:

- Open the Sandbox (you can create a free, anonymous temporary account with one just one click!)
- Select "Convex Lisp" as the language, if not already selected 
- Type example code from this guide into the Sandox input window as you progress
- You will see outputs from Convex in the output window. we use `=>` to indicate expected outputs in the examples below.

## Lisp basics

Lisp is fundamentally a language about expressions. All code in Lisp is ultimately an expression that can be evaluated to get a resulting value (or maybe an error, if something went wrong...). So let's take a quick tour through the most common types of expressions.

### Literal values

The simplest expression is simply a constant literal data value, which evaluates directly to itself!

```clojure
1
```

If you type the number `1` in the Sandbox and execute it, the result is simply the literal value itself:

```clojure
=> 1
```

Strings can be used as literals by enclosing them in double quotes:

```clojure
"Hello World!"
=> "Hello World!"
```

Individual characters can be used as literals by preceding them with a backslash (`\`). You can also specify 16-bit unicode characters in the form `\uXXXX` where XXXX is a 4-character hex string.

```clojure
\a
=> a

\u0065
=> e

```

Keywords are special literal values that are intended for use as keys in hash maps, sets, etc. They can also be conveniently used as field names in records, as special unique marker values, or as a member of a defined set of values like "enums" in other languages.

```clojure
:foo
=> :foo
```

The special values `true` and `false` are the two usual Boolean values:
```
true
=> true

false
=> false
```

The special value `nil` is considered as the empty / missing value. It is also considered the same as `false` when used in conditional expressions which is often surprisingly useful: more on that later!

```
nil
=> nil
```

Finally, there is also support for byte data encoded in hexadecimal (we call these "Blob literals" because they can technically be arbitrary Binary Large OBjects). Any hex string with an even number of digits is valid.

```clojure
0xff1234
=> 0xff1234

;; This is OK, and results in a zero length Blob
0x
=> 0x
```

Blob literals are somewhat unusual as a data type, but are very convenient for many reasons in Convex: specifying addresses of users or smart contracts, validating cryptographic hashes against exact values etc. Using blob literals directly is also much more efficient than encoding/decoding binary data in some other format such as hex strings.

### Symbols

Symbols are named references to value in the Convex programming environment. When used as expressions, they look up the value that they refer to in the current context. Usually you would first use `def` to create a new value in the environment.

```clojure
;; Define a symbol with 'def'
(def a 100)
=> 100

;; 'a' now refers to 100 in the current environment
a
=> 100
```

If you try to evaluate a symbol that has no corresponding definition in the environment, you will get an UNDECLARED error:

```
bad
=> ERROR (UNDECLARED)
=> 'bad' is undeclared.
```

Some special symbols are provided by Convex to make it easier to access special features of the CVM. By convention, and in order to make them stand out when reading Convex Lisp code, these symbol names start and end with asterisks (`*`).

```clojure
;; Get the available balance of the current Account via the special symbol '*balance*'
*balance*
=> 97996220

;; Get the Address of the current Account via the special symbol '*address*'
*address*
=> 0x924a3A1bE387E591d364CA4ac596004811b2Fcae6bF261355eEF15Ccf1108613
```

### Functions

Functions can be called in an expression by placing the function name in a list before the arguments to a function. Usually, the function is specified by a Symbol:

```clojure
;; Call the 'inc' function which adds 1 to an integer value.
(inc 10)
=> 11
```

This construct of applying a function by forming an expression with the function it the beginning of a list followed by its arguments is classic Lisp syntax. This may be surprising to people who are new to Lisp but are used to languages such as C, Java or JavaScript. If it helps, you can think of simply moving the opening parenthesis of the argument list before the function name:

```clojure
// In a C-like language
inc(10)

;; In Lisp
(inc 10)
```

Why do we do this? It turns out that being able to express the whole function application expression as a list is extremely useful for more advanced techniques such as macros and code generation. A topic for later.

The Convex core runtime library provides a wide variety of useful functions that you can see in the [Reference](https://convex.world/#/documentation/reference). Some simple examples to try out:

```clojure
;; Addition: '+' is a variable arity function that cab take multiple arguments
(+ 1 2 3)
=> 10

;; There are various predicate functions that test values and return a boolean
;; e.g. 'str?' tests if the argument is a String
(str? "Hello")
=> true
```

You can easily define your own functions with `defn`:

```clojure
;; Define a 'square' function which multiplies its argument by itself
(defn square [x] (* x x))

;; Apply the new function
(square 111)
=> 12321
```

You can also create anonymous functions and use them directly with the `fn` special form. The function below is equivalent to the `square` example above, but we don't give it a name at any point.

```clojure
((fn [x] (* x x)) 111)
=> 12321
```

### Data structures

Convex provides a powerful set of data structures as part of the CVM. In fact, one reasons Convex performs so well is due to the power of the data structures.

All Convex data structures are *immutable* - functions which make a change to a data structure actually create a new data structure. There are some clever tricks which mean that most of the data in large data structures doens't need to be cloned, which makes this extremely fast.

#### Vectors

A Vector is an ordered sequence of values. You can create a vector as a literal value by enclosing any list of expressions with square brackets `[...]`

```clojure
;; A vector containing the numbers 1, 2 and 3
[1 2 3]
=> [1 2 3]

;; The empty vector
[]
=> []

;; A vector generated by evaluating two expressions
[(+ 1 2) (+ 3 4)]
=> [3 7]

;; Vectors can contain arbitrary element types, including other nested vectors
[1 :foo ["Hello" true]]
=> [1 :foo ["Hello" true]]
```

There are many functions in the core library that work with Vectors. Some simple examples:

```
;; Get an element from a vector at the specified index
(get [:foo :bar :baz] 1)
=> :bar

;; Test if a value is actually a vector
(vector? [1 2])
=> true

;; Concatenate two vectors
(concat [1 2] [3 4])
=> [1 2 3 4]

;; Add a new element to a vector (at the end)
(conj [1 2] 3)
=> [1 2 3]
```

In general, you should use Vectors whenever you need to store an ordered sequence of values. They are the fastest data structure for indexed lookup, and for appending a single element to the end with `conj`.

#### Maps

A map associates a finite set of keys with a value for each. You can create a map as a literal value by enclosing any list of expressions with curly braces `{...}`

```clojure
;; A map with two key/value pairs
{:foo 1 :bar 2}
=> {:foo 1 :bar 2}

;; An empty map
{}
=> {}
```

Maps are designed for efficient lookup of values based on keys. If the specified key does not exist, either nil or an optional 'not found' value can be returned.

```clojure
;; Get the value from a map for a specified key
(get {:foo 1 :bar 2} :foo)
=> 1

;; Get a key that doesn't exist
(get {:foo 1 :bar 2} :batman)
=> nil

;; Get using an optional 'not-found' result
(get {:foo 1 :bar 2} :batman :MISSING)
=> :MISSING
```

You can also use a map as a function! This can be convenient since it can save you from writing boilerplate code just to lookup up values in a map.

```clojure
;; Define a map in the environment with the symbolic name 'my-map'
(def my-map {:foo 13 :bar 23 :baz 41})

;; Use 'my-map' to look up values as if it is a function
(my-map :baz)
=> 41
```

There are a variety of useful functions in the core library that are designed to work with maps. Some examples to try:

```clojure
;; Update a map with a new key / value association using 'assoc'
(assoc {:foo 1 :bar 2} :baz 3)
=> {:foo 1, :baz 3, :bar 2}

;; Remove a key/value pair from a map with 'dissoc'
(dissoc {1 2 3 4} 1)
=> {3 4}

;; Count the number of key/value pairs in a map
(count {:foo 1 :bar 2})
=> 2

;; Get a vector of keys for the map
(keys {:foo 1 :bar 2})
=> [:foo :bar]
```

In general, you should use Maps whenever you need to look up values with a specific key, and the order doesn't matter. Maps support very efficient lookup by key. `assoc` and `dissoc` are also very efficient.

#### Sets

Sets are an unordered collection of values. You can create a set as a literal value by enclosing any list of expressions with a hash symbol followed by curly braces `#{...}`

```clojure
;; A set of 3 numbers
#{1 2 3}
=> #{1 2 3}

;; The empty set
#{}
```

The most common operation with a set is to test whether it contains a specific value.

```clojure
;; 'get' returns the value from a set if it is present, or nil otherwise
(get #{1 2} 2)
=> 2

(get #{1 2} 3)
=> nil

;; An optional 'not-found' value can also be added
(get #{1 2} 3 :OOPS)
=> :OOPS
```

You can also use a set as a function, in which case it will return a boolean value indicating whether the set contains the specified argument.

```clojure
(def my-set #{1 2 3})

(my-set 1)
=> true

(my-set 10)
=> false
```

Some examples of functions from the core library that work with sets:

```clojure
;; Add a value to a set
;; NOTE: when displaying the elements of the resulting set, the order is not guaranteed
(conj #{1 2 3} 4)
=> #{1 2 3 4}

;; Use 'into' to add a sequence of extra elements to a set (will de-duplicate automatically)
(into #{1 2 3} [3 4 5])
=> #{1 2 3 4 5}

;; Remove an element from a set with 'disj'
(disj #{1 2 3} 2)
=> #{1 3}
```

#### Lists

Lists are ordered sequences of elements, just like Vectors. However, Lists are specially designed to be used for representing code.

If you enter a List directly in the Sandbox, it will get executed as an expression:

```clojure
(inc 10)
=> 11
```

This is helpful for executing code, but not useful if you want to use Lists as a data structure! If you want to stop a List from being automatically evaluated, you can *quote* the List by adding the character `'` before this list. This tells Convex to interpret the List as a literal data structure:

```clojure
'(inc 10)
=> (inc 10)
```

Some other ways of constructing a List:

```clojure
;; The empty list works directly as a literal
()
=> ()

;; Convert a Vector to a List
(vec [1 2 3])
=> (1 2 3)

;; Create a List using 'cons' which adds a value to the front of any sequential collection
(cons a '(b c))
=>(a b c)
```

In normal code, you should generally prefer Vectors over Lists for storing data. Lists should mainly be used for generating code - in macros, for example.

### Special forms

Convex Lisp includes a number of special forms, that implement behaviour that can't be achieved using regular functions from the core library.

#### Condtionals

General purpose languages need some way of controlling conditional execution of code, and Convex Lisp is no exception. 

Convex Lisp provides an `if` macro that evaluates a conditional expression, and then executes one of two other expressions depending on whether the value of the first is true of false.

```clojure
;; A simple if expression that always takes the 'true' branch
(if true 20 30)
=> 20

;; A simple if expression that always takes the 'false' branch
(if false 20 30)
=> 30

;; If no false branch is provided, the 'if' expression returns 'nil' in this case
(if false 20)
=> nil
```

Conditionals branch based on whether the conditional expression evaluated is truthy or falsey.

- A value is considered **falsey** if it is either the boolean value `false` or `nil`
- Any other value is considered **truthy**, including the boolean value `true` but also `[]`, `1`, `:foo` etc.

Why do we do this rather than only allowing booleans? Well, it turns out that in a lot of situations, you want to branch based on whether a result is `nil` or non-`nil` (e.g. when you want to look up a value in a database). We could force developers to do a `(nil? x)` check to coerce each such result into a boolean, but this adds overhead and boilerplate code. Instead, we make conditionals work with truthy and falsey values directly, so that such conversion code is usually unnecessary.

The `cond` special form works like `if`, but allows multiple tests, and can optionally provide a default result that 

```clojure
(cond 
  false 10
  false 20
  true 30
  false 40)
=> 30
  
;; You can provide a default result if all tests fail
(cond
  false 10
  false 20
  "Nothing matched")
=> "Nothing Matched"
```

Implementation note: `if` is actually a macro that expands to a `cond` form under the hood. So technically, `cond` is the lower level special form. In practice, it is usually more convenient and intuitive to use `if`.



#### Do blocks

The `do` special form groups a number of expressions into a single expression and returns the value of the last expression (or `nil` if there are zero expressions). Results from earlier expressions are discarded. Typically, the earlier expressions are included in order to perform some side effect.

```clojure
;; A do Block with three expressions inside, all are executed but only the last result is returned.
(do 1 2 3)
=> 3

;; A do block with zero expressions always returns 'nil'
(do)
=> nil

;; Side effects from ealier expressions are visible in later expressions
(do (def a 100) (+ a a))
=> 200
```

The `do` form serves a similar purpose to a code block in many other languages. It's useful for grouping a number of statements together for the purposes of side effects.


#### Let and local variables

The `let` special form allows you to define local variables in the scope of a code block. Apart from the local variable definition, a `let` block is similar to a `do` block.

```clojure
;; 'let' expression that defines 'x' in its body.
(let [x 10] (* x x))
=> 100

;; You can define multiple local variables in one binding expression
(let [x 10
      y (* x x)] 
  (+ x y))
=> 110

;; Local binding ceases to exist immediately after the '(let ...)' form
(do (let [x 10]) x)
=> ERROR (UNDECLARED)
=> 'x' is undeclared.

;; Local bindings take precedence over definitions in the surrounding environment
(def foo 13)

foo
=> 13

(let [foo 17] foo)
=> 17
```

You can also use `set!` to set the binding of a local variable. This value will last until the end of the current binding form (a surrounding `let` block, or returning from a function body).

```clojure
(let [a 10]
  (set! a 20)
  a)
=> 20
```

#### Def and the environemnent

We've already seen the `def` special form in a couple of examples, where it was used to set the value of a symbol:

```clojure
(def message "Hello!")

message
=> "Hello"
```

The key difference with `def` compared to `let` is that it sets the value in the persistent environment, rather than just making a temporary local binding. The environment in Convex is special:

- Every user Account gets its own *independent* environment. Two different users can define their own `message` and they will see their own version. 
- You can only *modify* your own environment, using a digitally signed transaction for the relevant account. 
- It is possible for anyone with access to the Convex network to *observe* any user environments on a read-only basis - so while nobody can modify your data, it isn't private!
- The environment is *persistent* between transactions. Unless you choose to delete it, a definition in your environment will stay there forever. You can therefore use definitions in the environment to store data.
- Definitions in the environment use some amount of *memory* on-chain. While small data allocations are typically not very expensive, care should be taken before storing large data structures in the environment.

With memory 

## Evaluation

We've looked at the basic constructs of Convex Lisp, but it's worth taking a moment to look at the way that code is evaluated in Lisp. This section delves into some implementation details, and what makes Convex Lisp special.

### Code is Data

A key idea in Lisp is that 'Code is Data'. The language syntax is expressed in the data structures of the language.


