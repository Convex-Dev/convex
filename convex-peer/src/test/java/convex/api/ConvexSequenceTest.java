package convex.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import convex.core.ErrorCodes;
import convex.core.Result;
import convex.core.crypto.AKeyPair;
import convex.core.cvm.Address;
import convex.core.cvm.transactions.ATransaction;
import convex.core.cvm.transactions.Invoke;
import convex.core.data.SignedData;
import convex.core.lang.Reader;

/** Tests client-side transaction sequence reservation and recovery. */
public class ConvexSequenceTest {

	@Test
	public void testTimeoutDoesNotRewindReservedSequence() throws InterruptedException {
		ControlledConvex convex=new ControlledConvex(new CompletableFuture<>());
		convex.setNextSequence(42);

		Result result=convex.transactSync(transaction(convex),0);

		assertEquals(ErrorCodes.TIMEOUT,result.getErrorCode());
		assertEquals(42L,convex.cachedSequence());
	}

	@Test
	public void testOnlySequenceRejectionInvalidatesReservation() throws InterruptedException {
		ControlledConvex convex=new ControlledConvex(
				CompletableFuture.completedFuture(Result.error(ErrorCodes.ASSERT,"Expected failure")));
		convex.setNextSequence(42);

		Result result=convex.transactSync(transaction(convex),1000);

		assertEquals(ErrorCodes.ASSERT,result.getErrorCode());
		assertEquals(42L,convex.cachedSequence(),"A CVM error still consumes its transaction sequence");

		convex.observeTransactionResult(Result.error(ErrorCodes.SEQUENCE,"Bad sequence"));
		assertNull(convex.cachedSequence());
	}

	private static ATransaction transaction(Convex convex) {
		return Invoke.create(convex.getAddress(),ATransaction.UNKNOWN_SEQUENCE,Reader.read("*address*"));
	}

	/** A client whose transaction completion is controlled entirely by the test. */
	private static final class ControlledConvex extends ConvexDirect {
		private final CompletableFuture<Result> result;

		private ControlledConvex(CompletableFuture<Result> result) {
			super(Address.create(1234),AKeyPair.createSeeded(1234),null);
			this.result=result;
		}

		@Override
		public synchronized CompletableFuture<Result> transact(SignedData<ATransaction> signedTransaction) {
			return result;
		}

		private Long cachedSequence() {
			return sequence;
		}
	}
}
