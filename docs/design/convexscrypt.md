## Convex Scrypt

Scrypt is a modern, functional scripting language for decentralised applications.

It is designed to be easy to use and familiar to developers who have worked with Java or JavaScrypt, while still providing access to the full power of the Convex platform.

```javascript
// This is sample Scrypt code
for (int i=0; i< 10; i++) {
  deploy(new-token({:name "Token"+i , :supply 1000000, :owner me}));
}
```

### Design Objectives

* Must be a front-end to the CVM. Should not require language-specific features in CVM.
* Must work with Convex Lisp defined objects (ability to cross-utilise libraries etc.)
* Should be familiar to user of ALGOL-like langauges (C, Java, JavaScript)
* Must support infix arithmetic
* Should match expectations of Java or JavaScript developers where possible
* Should map quite closely to Convex Lisp semantics and underlying operations

### Architecture

Scrypt is a language implemented primarily as a front-end to underlying CVM capabilities. It does not require special CVM support: the CVM is agnostic to whether compiled Ops were written originally in Scrypt, Convex Lisp, or any other language.

The Scrypt implementation incudes:

- A custom PEG parser that translates source code to an AST representation equivalent to Convex Lisp
- A REPL-based user interface, intended for development and operational usage
- A test suite and example code

Scrypt re-uses key capabilities from the rest of Convex:

- CVM runtime environment and object model semantics
- Backend Compiler and AST model (as used by Convex Lisp)

### Open questions

* Can we use char literals in Java style like `'c'` or does this clash with symbols too much?

### Syntax examples

#### Variable declaration
```javascript
def x = 1;

def x = (x) -> x;

def x = do {
  map(inc, [1, 2, 3]);
  
  1; 
};
```

#### If & else
```javascript
if (x > 1) {
  x;
} else {
  y;
}
```

#### When
```javascript
when (x > 1) {
  x;
}
```

#### Block
```javascript
{
  doX();
  doY();
  z;
}
```

#### Do
```javascript
do {
  doX();
  doY();
  z;
}
```

#### Anonymous function
```javascript
fn (x, y) { 
  x + y; 
}
```

#### Lambda
```javascript
(x) -> x

(x, y) -> x + y

(x, y) -> do {
  doX();
  doY();
  z;
}
```

#### Function declaration
```javascript
defn f(x) {
  x;
}
```