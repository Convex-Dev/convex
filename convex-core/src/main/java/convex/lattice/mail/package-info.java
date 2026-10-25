/**
 * The {@code :mail} lattice region: asynchronous DID-addressed messaging.
 *
 * <p>A mailbox is an index of two-state registers keyed by {@code issued-at ‖ hash}:
 * a signed {@link convex.lattice.mail.MailEnvelope} deposited by anyone, replaced by
 * the recipient's signed {@link convex.lattice.mail.MailReceipt}. Bodies are encrypted
 * with {@link convex.core.crypto.ECIES}. The design is
 * {@code docs/proposals/lattice-mail.md}.</p>
 */
package convex.lattice.mail;
