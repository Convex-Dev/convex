/**
 * Convex Core – Reference implementation of the Convex Lattice Protocol.
 *
 * <p>Convex is next-generation decentralised infrastructure: a Turing-complete
 * global state machine, database and execution engine. Every peer
 * maintains an identical, append-only history of state transitions while
 * converging on the same beliefs in logarithmic time in realtime –
 * without leaders, mining, or staking.</p>
 * 
 * <p>The Lattice is a global, cryptographically-secured CRDT that achieves strong 
 * eventual consistency through deterministic belief merging. 
 * Inspired by mathematical lattices, it converges in logarithmic time 
 * without leaders or probabilistic finality, 
 * using signed beliefs, structural sharing, 
 * and Merkle proofs to form a single, ever-refining crystal of decentralised truth.</p>
 *
 * <p>This module is the complete, specification-conformant engine that powers
 * Convex peers and lattice applications. It includes:</p>
 *
 * <ul>
 *   <li>The Convex Virtual Machine (CVM) – featuring a pure, auditable, juice-priced Lisp</li>
 *   <li>Persistent vector / map / set data structures with O(log n) structural sharing</li>
 *   <li>Belief propagation and consensus with Convergent Proof of Stake</li>
 *   <li>CAD3 encoding format and related utilities</p>
 *   <li>Lattice technology core implementation for global-scale data structures</li>
 *   <li>Cryptographic tools including hashing and  Ed25519 signatures</li>
 *   <li>Deterministic state trie, account model, and governance system</li>
 * </ul>
 *
 * @author Mike Anderson and Contributors
 */
module convex.core {
	exports convex.core;
	exports convex.core.store;
	exports convex.core.data.util;
	exports convex.core.crypto;
	exports convex.core.crypto.util;
	exports convex.core.cpos;
	exports convex.core.data.type;
	exports convex.core.init;
	exports convex.core.cvm;
	exports convex.core.cvm.exception;
	exports convex.core.cvm.transactions;
	exports convex.core.util;
	exports convex.core.exceptions;
	exports convex.core.message;
	exports convex.core.data.prim;
	exports convex.core.text;
	exports convex.core.lang.reader;
	exports convex.core.crypto.wallet;
	exports convex.core.cvm.ops;
	exports convex.core.data;
	exports convex.core.lang;
	exports convex.core.json;
	exports convex.etch;
	exports convex.lattice.fs;

	requires transitive org.antlr.antlr4.runtime;
	requires org.bouncycastle.pkix;
	requires transitive org.bouncycastle.provider;
	requires org.bouncycastle.util;
	requires java.base;
}