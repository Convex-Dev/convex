package convex.lattice.mail;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AccountKey;
import convex.core.data.Hash;

/**
 * An opened message: an envelope the recipient could decrypt, with its payload and
 * the result of checking the sender claim.
 *
 * @param hash Envelope hash, the message identity on the lattice
 * @param to Recipient DID
 * @param from Claimed sender DID, or null for an unattributed envelope
 * @param signer Key that signed the envelope
 * @param fromAuthorised Whether {@code signer} is authorised for {@code from}; false when {@code from} is null
 * @param issuedAt Sender timestamp
 * @param payload Decrypted payload map, or the plain body
 * @param encrypted Whether the body was encrypted
 */
public record MailMessage(Hash hash, AString to, AString from, AccountKey signer, boolean fromAuthorised,
		long issuedAt, ACell payload, boolean encrypted) {
}
