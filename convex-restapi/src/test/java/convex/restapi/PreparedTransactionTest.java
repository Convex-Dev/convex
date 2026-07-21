package convex.restapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import convex.core.cvm.Address;
import convex.core.cvm.transactions.ATransaction;
import convex.core.cvm.transactions.Invoke;
import convex.core.data.Blob;
import convex.core.data.Format;
import convex.core.data.SignedData;
import convex.core.exceptions.BadFormatException;
import convex.core.lang.Reader;

public class PreparedTransactionTest {

	@Test
	public void testCompleteDataRoundTrip() throws BadFormatException {
		ATransaction transaction = Invoke.create(Address.create(42), 7, Reader.read("(map inc [1 2 3])"));
		Blob data = Format.encodeMultiCell(transaction, true);
		Blob signingMessage = SignedData.getMessageForRef(transaction.getRef());

		assertEquals(transaction, PreparedTransaction.decode(data, signingMessage));
	}

	@Test
	public void testRejectsMismatchedSigningMessage() {
		ATransaction transaction = Invoke.create(Address.create(42), 7, Reader.read("(+ 1 2)"));
		ATransaction other = Invoke.create(Address.create(42), 7, Reader.read("(+ 1 3)"));
		Blob data = Format.encodeMultiCell(transaction, true);
		Blob otherMessage = SignedData.getMessageForRef(other.getRef());

		BadFormatException error = assertThrows(BadFormatException.class,
				() -> PreparedTransaction.decode(data, otherMessage));
		assertEquals("Transaction data does not match the supplied hash", error.getMessage());
	}
}
