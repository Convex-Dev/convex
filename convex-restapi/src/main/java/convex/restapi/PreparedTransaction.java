package convex.restapi;

import convex.core.cvm.transactions.ATransaction;
import convex.core.data.ACell;
import convex.core.data.Blob;
import convex.core.data.SignedData;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.PartialMessageException;
import convex.core.message.Message;

/**
 * Stateless decoding for transactions returned by a prepare endpoint.
 *
 * <p>The complete multi-cell value must come back from the client on submit.
 * Decoding is deliberately storeless: public transaction preparation must not
 * populate, or depend on, the Peer's primary store.</p>
 */
public final class PreparedTransaction {

	private PreparedTransaction() {
	}

	/**
	 * Decodes complete transaction data and verifies its signing message.
	 *
	 * @param data Complete multi-cell transaction encoding
	 * @param expectedMessage Reference encoding signed by the client
	 * @return Decoded transaction
	 * @throws BadFormatException If data is partial, malformed, not a transaction,
	 *                            or does not match the signing message
	 */
	public static ATransaction decode(Blob data, Blob expectedMessage) throws BadFormatException {
		final ACell value;
		try {
			// Reuse normal complete-message decoding. Passing no store ensures that
			// missing branches fail instead of being read from or attached to primary.
			value = Message.create(data).getPayload(null);
		} catch (PartialMessageException e) {
			throw new BadFormatException("Transaction data is incomplete: " + e.getMessage(), e);
		}

		if (!(value instanceof ATransaction transaction)) {
			throw new BadFormatException("Submitted data is not a transaction");
		}

		Blob actualMessage = SignedData.getMessageForRef(transaction.getRef());
		if (!actualMessage.equals(expectedMessage)) {
			throw new BadFormatException("Transaction data does not match the supplied hash");
		}
		return transaction;
	}
}
