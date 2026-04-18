/**
 * CVM exceptional values — first-class results of non-local control flow.
 *
 * <p>Unlike Java exceptions, these are immutable CVM values returned through
 * the execution stack: {@code ErrorValue}, {@code HaltValue}, {@code ReturnValue},
 * {@code RollbackValue}, {@code RecurValue}, and {@code TailcallValue}. They
 * implement Convex Lisp's {@code halt}, {@code return}, {@code rollback},
 * {@code recur}, and error-handling semantics, and are observable on-chain.</p>
 */
package convex.core.cvm.exception;
