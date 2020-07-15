## Coding principles

### Immutable first

Everything is an immutable data structure, with the exception of necessary
mutable values for either:
1. Locally managed state
2. Lazy computation / caching

### Trust the JVM

We unashamedly exploit the JVM as an excellent runtime platform for decentralised sysytems. 
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

Any of the the above principles can be overridden by reason, evidence and common sense.

"The three great essentials to achieve anything worthwhile are, first, hard work; second, stick-to-itiveness; third, common sense."
― Thomas Edison