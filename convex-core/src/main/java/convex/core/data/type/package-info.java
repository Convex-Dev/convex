/**
 * CVM type system — runtime type descriptors and membership checks for CVM values.
 *
 * <p>Each {@code AType} represents a class of CVM values (e.g. {@code Long},
 * {@code Address}, {@code Vector}) and supports type predicates, default values,
 * and conversion logic. Primarily used internally by the CVM runtime and
 * {@link convex.core.lang.RT} dispatch.</p>
 */
package convex.core.data.type;