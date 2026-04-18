/**
 * Specialised checked and unchecked exceptions used across Convex libraries.
 *
 * <p>Covers decoding failures ({@code BadFormatException}), storage issues
 * ({@code MissingDataException}), validation errors, and general protocol-level
 * faults. These are Java-side host exceptions — distinct from
 * {@link convex.core.cvm.exception CVM exceptional values}, which are first-class
 * values within the CVM.</p>
 */
package convex.core.exceptions;