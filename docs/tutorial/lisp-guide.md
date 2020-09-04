# Convex Lisp Guided Tutorial

This guide is intended for developers interested in learning about Convex Lisp. We will take you through the basics of the language, all the way through to designing and deploying a simple smart contract!

## Setup

Using the [Sandbox](https://convex.world/#/sandbox) is the easiest way to experience Convex Lisp. We recommend that you try it out as you go through this guide: It's more fun to get instant feedback and try out new ideas quickly! To do this:

- Open the Sandbox (you can create a free, anonymous temporary account with one just one click!)
- Select "Convex Lisp" as the language, if not already selected 
- Type example code from this guide into the Sandox input window as you progress
- You will see outputs from Convex in the output window. we use `=>` to indicate expected outputs in the examples below.

## Lisp basics

Lisp all about expressions. All code in Lisp is ultimately an expression that can be evaluated to get a resulting value (or maybe an error, if something went wrong...). So let's take a quick tour through the most common types of expressions, and the values that they produce.

### Literals

The simplest type of expression is a constant literal data value, which evaluates directly to itself!

```clojure
1
```

If you type the number `1` in the Sandbox and execute it, the result is the number one itself:

```clojure
=> 1
```

Convex can handle double precision floating point numbers as well:

```clojure
1.5
=> 1.5
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

```clojure
true
=> true

false
=> false
```

The special value `nil` is considered as the empty / missing value. It is also considered the same as `false` when used in conditional expressions which is often surprisingly useful: more on that later!

```clojure
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

```clojure
bad
=> ERROR (UNDECLARED)
=> 'bad' is undeclared.
```

Some *special symbols* are provided by Convex to make it easier to access special values provided by the CVM. By convention, and in order to make them stand out when reading Convex Lisp code, these symbol names start and end with asterisks (`*`).

```clojure
;; Get the available balance of the current Account via the special symbol '*balance*'
*balance*
=> 97996220

;; Get the Address of the current Account via the special symbol '*address*'
*address*
=> 0x924a3A1bE387E591d364CA4ac596004811b2Fcae6bF261355eEF15Ccf1108613
```

### Functions

Functions in Convex Lisp are the fundamental objects that represent computation: algorithms that can be applied to transform input data into output data.

#### Function application syntax

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

#### The Core library

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

#### Defining functions

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

In general, you should use Vectors whenever you need to store an ordered sequence of values. They are the fastest data structure for indexed lookup, and for appending a single element to the end with `conj`. Vectors are the natural Convex equivalent to what are often called "arrays" or "tuples" in other languages.

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

#### Conditionals

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

If you want to define functions specifically, you can use `defn`:

```clojure
;; Define a Euclidean distance function
(defn dist [x y]
  (sqrt (+ (* x x) (* y y)))
  
(dist 3.0 4.0)
=> 5.0
```

`defn` is actually a simple macro that converts `(defn f [x] ...)` into `(def f (fn [x] ...)`. So you never really need `defn`: it's just a convenient shortcut for defining functions and can make your code more readable.

#### Loop and recur

When you want to iteratively re-evaluate an expression, you can use `loop` and `recur`. 

```clojure
;; This is a slow way to calculate an integer square root. Please use 'sqrt' in real code.
(loop [i 0]               ;; initialise 'i' with zero
  (if (< (* i i) 100)     ;; test if 'i' is too low to be the square root
    (recur (inc i))       ;; recur - incrementing 'i' for the next loop iteration
    i))                   ;; return 'i'
=> 10
```

Loop works like `let` in that it establishes local loop variable bindings that you can use in each iteration. `recur` will jump back to the start of the loop, updating the loop variables. It is normal to use a conditional expression to determine whether to recur or not. The value returned from the loop will be the value of the last expression executed (in this case `i`)

You can also use `recur` to repeat evaluation of a function body:

```
;; A factorial function using an accumulator
(defn factorial [acc n]
  (if (<= n 1) 
    acc
    (recur (* acc n) (dec n))))
    
(factorial 1 10)
=> 3628800
```

`recur` implements "tail call optimisation", i.e. it recurs without consuming any stack space. This is important if you want to perform many iterations: stack depth on the CVM is a limited resource and your transactions will fail if you consume too much. `recur` is your friend.

#### Quoting

Sometimes, you want to use a symbol itself rather than the thing that the symbol refers to. In these cases, you can 'quote' the symbol.

```clojure
(def a 10)

;; Use the value defined for the symbol a
a
=> 10

;; Use the Symbol a itself
(quote a)
=> a

;; The ' notation also quotes a Symbol
'a
=> a
```

You can also quote lists and other data structures - which returns these data structure *without* evaluating them.

```clojure
;; Evaluate a list normally
(+ 1 2 3)
=> 6

;; Quote a list without evaluating it
'(+ 1 2 3)
=> (+ 1 2 3)
```

It is possible to 'unquote' within a quoted expression using the tilde (`~`), which has the effect of evaluating the unquoted part normally. 

```clojure
(quote [(+ 1 2 3) ~(+ 1 2 3)])
=> [(+ 1 2 3) 6]
```

## Evaluation

We've looked at the basic constructs of Convex Lisp, but it's worth taking a moment to look at the way that code is evaluated in Lisp. This section delves into some implementation details, and what makes Convex Lisp special.

### Code is Data

A key idea in Lisp is that 'Code is Data'. The language syntax is expressed in the data structures of the language. This property is known as *homoiconicity*, and is one of the features of Lisp that makes in uniquely powerful.

You can use the `eval` function to execute code that is provided as data:

```clojure
;; Regular code
(+ 1 2)
=> 3

;; Code expressed as a list
'(+ 1 2)

;; Execute code using eval
(eval '(+ 1 2))
=> 3
```

The power of 'Code is Data' starts to become apparent when you relise that since you can use code to construct data, you can equivalently use code to construct code.

```
(defn make-code [operation arguments]
   (cons operation arguments))
   
(make-code '+ [1 2 3 4])
=> '(+ 1 2 3 4)

(eval (make-code '* [1 2 3 4]))
=> 24
```

Hopefully, it is now clear why Lisp puts parentheses *before* the function name: it means that expressions can trivially be constructed as a single list, but prepending (`cons`) the desired function name to the list of arguments. Code generation becomes simple: just construct the expression you want!

### SECURITY: Important note for `eval`

You should **NEVER** use `eval` on data from an untrusted source. It will be able to execute anything that you can in your environment - including helping itself to any coins and tokens controlled by your account. If you are unsure whether this is a risk or not, a good rule is that you should avoid using `eval` at all in any environment with economically valuable assets.




## Functional Programming

Convex Lisp is designed to support functional programming. We can think of functional programming as a paradigm where:

- Functions are first class objects in the language
- Programs are developed by composing pure functions and immutable data
- Mutable data and side effects are generally avoided

Functional programming offers us a number of major advantages:

- Code expressed using pure functions is easier to reason about and test, because you don't have to worry about internal or external mutable state that might affect behaviour.
- It is often much shorter and quicker to read/write than equivalent imperative code
- Immutable data is a *great* fit for the CVM which is deigned around immutable, cryptographically verified data structures.

Here's a simple example of functional programming, where we define a first-class function `square` and then pass it to another function to achieve our intended result:

```clojure
;; Define a function that squares a number
(defn square [x] (* x x))

;; Apply the square function to each element of a vector
(map square [1 2 3 4])
=> [1 4 9 16]
```

We can get a bit more sophisticated, and use functions to create other functions:

```
;; Function to build a function combining a map and a reduce
(defn mapreducer [init reducer mapper]
  (fn [vals]
    (reduce reducer init (map mapper vals))))

;; Build a 'sum-of-squares' function using our 'mapreducer'    
(def sum-of-squares (mapreducer 0 + square))   

(sum-of-squares [1 10 100 1000])
=> 1010101
```

`map` and `reduce` are both very powerful tools for functional programming, and in many cases can replace the need to implement imperative loops. They also help to avoid the dreaded "off by one" errors!

## Actors

Actors are autonomous programs that live on the CVM. Most importantly, they are the "workers" that enable to operation of smart contracts.

Actors have quite similar capabilities to Users:

- They have an Account on the CVM, with an address like `0x6f7f341B648F36B11C8AE735997Aeb868124951Beded1C5371C89cD1d3AA9E6b1`
- They have their own dynamic environment, which can contain definitions and data just like a User's environment.
- They can control digital assets, coins and memory allocations

### Creating an Actor

To create an Actor, you need to deploy some code to initialise the actor. The code is executed when the Actor is deployed, and can be used to set up the environment of the actor, e.g. defining new values or functions.

```clojure
;; Deploy an actor, get the resulting address
(deploy-once '(def some-data "Hello"))
=> 0x1416E4b915D0aeCAa9362a0d37612Bf924fA4BC01449827Ae907154f8317113B

;; This is undeclared, since some-data exists in the Actor's environment, not ours
some-data
=> UNDECLARED

;; we can look up the data in the new Actor's environment however:
(lookup 0x1416E4b915D0aeCAa9362a0d37612Bf924fA4BC01449827Ae907154f8317113B 'some-data)
=> "Hello"
```




Your initialisation code *must* set up any capabilities you want the actor to have in the future: once deployed, you might not be able to make any further changes if you make a mistake (although it is possible to make Actor upgradable... more on this later).

### Calling Actor functions

Actors are more than just containers for data - they can be active participants in transactions. To create an Actor that exposes executable functionality to others, you need to `export` one or more functions. The following example is an Actor that allows callers to get and set a value

```clojure
;; define code for our Actor
(def actor-code
  '(do
     (def value :initial-value)
     
     (defn set [v]
       (def value v))
       
     (defn get []
       value)
       
     (export get set)))

;; Deploy the Actor and store the address as 'act' for convenient use later      
(def act (deploy actor-code))

;; Call 'get'
(call act (get))
=> :initial-value

;; Call 'set' with a new value
(call act (set :new-value))

;; Call 'get' again
(call act (get))
=> :new-value
```

This actor is pretty simple, but it demonstrates the key ideas:

- An Actor is an autonomous program, with its own execution environment
- You can export functions to allow users to interact with an Actor

### Building parameterised actors with `defactor`

Sometimes you want to pass parameters to construct an Actor. `defactor` lets you build an actor with parameters, and also provides some magic syntax to make declaring actors a bit more elegant:

```clojure
(defactor multiplier [x]
  (defn calc [y]
     (* x y))
     
  (export calc))

;; deploy multipliers with different parameters
(def times2 (deploy-once (multiplier 2)))
=> 0x80f0b6467DDbeB80a4A7D719A69949CFE9aC04bbdd6fD3A637Ef941Da39D86B9

(def times3 (deploy-once (multiplier 3)))
=> 0x322bc9a01E84922A9E27d77CCdE027a4b504F8B2a2AFf1D841608beac1263C5F

;; test them out!
(call times2 (calc 10))
=> 20

(call times3 (calc 10))
=> 30
```

Note the use of `deploy-once` rather than `deploy` in this case. `deploy-once` is like deploy, but if an actor with exactly the same initialisation code has been deployed already it will simply return a reference to the existing Actor. This is useful in cases where you don't need multiple copies of the same Actor - it is cheaper to simply refer to a singleton Actor.

Examples where `deploy-once` is a good idea:

- An Actor which has no mutable state (like the multiplier above)
- An Actor where you want multiple people to share the same instance, such as a chat room channel
- An Actor that benefits from more users providing liquidity, e.g. a digital asset exchange

### Sending funds to Actors

Like Users, Actor Accounts can have their own balance of funds. 

You can use the `transfer` function to transfer funds to an Actor, however this causes a problem: what if the Actor doesn't expect to receive funds, and there is not facility to transfer the funds elsewhere? This can cause coins to be irrevocably lost.

The better way to transfer funds is to "offer" them to the Actor you are calling, which then has to actively `accept` the funds to acknowledge receipt. Then, if coded correctly, there is no risk of funds being transferred that the receiving actor is unable to handle.

Below is a simple example of an Actor that accepts funds, keeps track of how much each caller has donated, and provides a payout mechanism to relay the funds to the given cause.

```
(defactor donations [cause]
  (assert (address cause)) ;; cause must cast to an address!

  (def all-donations {}) ;; a map of donation amounts

  (defn donate []
    (let [donation *offer*]
      (if (> donation 0)
        (let [past-donation (or (get all-donations *caller*) 0)]
          (def all-donations (assoc all-donations *caller* (+ past-donation donation)))
          (accept donation)
          (return "Thanks for your donation")))))
    
  (defn payout []
    (transfer cause *balance*))
    
  (export donate payout))
```

To use this Actor, it needs to be deployed and then called with the offer amount as an extra parameter to `call`:

```
;; A charity address that you want to be the beneficiary of donations
(def charity (address 0xf3393030Fc4252b7a91C5306f4d6670062384818145bF6239380bDDa3486055c))

;; Deploy the donations fund
(def charity-fund (deploy-once donations foo))
=> 0x62BccF81C937Bca59edFDcEd8fB3a5c0da895420d5932e5e688445A734aE68C5

;; Donate to charity
(call charity-fund 100000 (donate))
=> "Thanks for your donation"

;; See who has donated so far!
(lookup charity-fund 'all-donations)
=> {#addr 0x0f49f3005c950ff5822f6b51f7dfe31c18adbd90570afe99af680b8ce5e4996f 100000}
```

	
## Libraries

In most programming environments, it is helpful to bundle up code into libraries that can be shared and re-used. Convex Lisp is no exception, but takes a novel approach: Libraries are simply Actors!

This approach is powerful because:

- We make use of Convex as a global repository for libraries
- You can deploy libraries in the same way as you deploy Actors - no special tools needed!
- Libraries get all the same security and management guarantees as Actors
- You can use library functionality to access Actors

### Using libraries

Using libraries is easy! All you need to do is:

- `import` the library using its Address and give it a convenient alias e.g. `foo`
- Use symbols defined in the library by prefixing the symbol name with the alias e.g. `foo/bar`

```clojure
;; Import a library (in this case, the standard registry Actor)
(import 0xffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff :as reg)

;; Use a symbol from the library (in this case, count the number of registered accounts)
(count reg/registry)
=> 67081
```

### Deploying libraries

Deploying libraries is just like deploying an Actor, with a few key differences to note:

- You don't need to `export` any functions (unless you really want to enable `call`)
- `deploy-once` is normally recommended, since libraries should be deployed once and re-used.

```clojure
(def my-lib-address 
  (deploy-once
    '(defn distance [x y]
       (sqrt (+ (* x x) (* y y))))))
       
(import my-lib-address :as my-lib)

(my-lib/distance 3.0 4.0)
=> 5.0
```

### Important security note for libraries

A key difference between a `call` to an Actor function and running library code is the difference in *security context*:

- An Actor `call` runs code in the Actor's environment, with the Actor itself the current `*address*` (and the calling Account as `*caller*`)
- Library code runs in the environment of the current Account, i.e. `*address*` is unchanged

As a result of this: **DO NOT RUN LIBRARY CODE YOU DO NOT TRUST**. Library code can do anything that your Account can, including transferring away all your coins and tokens, or calling arbitrary smart contracts. If you have any doubt about the trustworthiness of library code, do not use it from an Account that controls any valuable assets.

