/**
 * CVM Operations — the primitive "machine code" executed by the Convex Virtual Machine.
 *
 * <p>Each operation is an immutable {@link convex.core.data.ACell} encoding a single
 * step of CVM execution (constants, lookups, function application, control flow,
 * definitions, etc.). Convex Lisp source code is compiled into trees of these ops
 * before execution.</p>
 */
package convex.core.cvm.ops;