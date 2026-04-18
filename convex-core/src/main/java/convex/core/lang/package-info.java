/**
 * Convex Lisp language implementation and CVM runtime.
 *
 * <p>Contains the reader entry point ({@link convex.core.lang.Reader}), the
 * compiler that lowers source forms into {@link convex.core.cvm.ops CVM ops},
 * the standard {@code Core} library of runtime functions, and the
 * {@link convex.core.lang.RT} dispatch layer that implements CVM semantics
 * over host values.</p>
 */
package convex.core.lang;