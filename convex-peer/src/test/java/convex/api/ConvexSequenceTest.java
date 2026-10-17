package convex.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.LongStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import convex.core.ErrorCodes;
import convex.core.Result;
import convex.core.crypto.AKeyPair;
import convex.core.cvm.Address;
import convex.core.cvm.transactions.ATransaction;
import convex.core.cvm.transactions.Invoke;
import convex.core.data.ACell;
import convex.core.data.SignedData;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.ResultException;
import convex.core.lang.Reader;

/** Tests client-side transaction sequence reservation and recovery. */
@Timeout(60) // Guard only: a regression to blocking would otherwise hang
public class ConvexSequenceTest {

	private static final long WAIT=10000;

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

	@Test
	public void testTransactDoesNotWaitForSequenceLookup() throws Exception {
		HeldConvex convex=new HeldConvex();

		CompletableFuture<Result> first=convex.transact(transaction(convex));
		CompletableFuture<Result> second=convex.transact(transaction(convex));
		Lookup lookup=convex.nextLookup();
		assertEquals(convex.getAddress(),lookup.origin());
		assertFalse(first.isDone());
		assertFalse(second.isDone());
		assertTrue(convex.sent.isEmpty(),"Nothing is submitted before the sequence is known");

		lookup.reply().complete(sequence(41));
		assertEquals(CVMLong.create(42),first.get(WAIT,TimeUnit.MILLISECONDS).getValue());
		assertEquals(CVMLong.create(43),second.get(WAIT,TimeUnit.MILLISECONDS).getValue());
		assertEquals(List.of(42L,43L),convex.sentSequences());
		assertTrue(convex.lookups.isEmpty(),"The queued transaction reuses the first lookup");
	}

	@Test
	public void testConcurrentTransactsGetConsecutiveSequencesInOrder() throws Exception {
		HeldConvex convex=new HeldConvex();
		int n=20;
		CyclicBarrier start=new CyclicBarrier(n);
		List<CompletableFuture<Result>> results=Collections.synchronizedList(new ArrayList<>());
		try (ExecutorService executor=Executors.newVirtualThreadPerTaskExecutor()) {
			for (int i=0;i<n;i++) {
				executor.execute(()->{
					try {
						start.await();
					} catch (Exception e) {
						throw new IllegalStateException(e);
					}
					results.add(convex.transact(transaction(convex)));
				});
			}
		}
		// Every transact call has returned while the lookup is still outstanding
		assertEquals(n,results.size());
		Lookup lookup=convex.nextLookup();
		assertTrue(convex.sent.isEmpty());
		for (CompletableFuture<Result> r: results) assertFalse(r.isDone());

		lookup.reply().complete(sequence(41));
		for (CompletableFuture<Result> r: results) {
			assertNull(r.get(WAIT,TimeUnit.MILLISECONDS).getErrorCode());
		}
		assertEquals(LongStream.rangeClosed(42,41+n).boxed().toList(),convex.sentSequences());
	}

	@Test
	public void testKnownSequenceSubmitsOnCallingThread() {
		HeldConvex convex=new HeldConvex();
		convex.setNextSequence(42);

		CompletableFuture<Result> result=convex.transact(transaction(convex));

		assertTrue(result.isDone(),"No queueing when the sequence is known");
		assertEquals(List.of(42L),convex.sentSequences());
		assertTrue(convex.lookups.isEmpty());
	}

	@Test
	public void testPreCompiledTransactionsSubmitInCallOrder() throws Exception {
		HeldConvex convex=new HeldConvex();
		convex.setPreCompile(true);
		convex.setNextSequence(42);
		ACell a=Reader.read("(a)");
		ACell b=Reader.read("(b)");

		CompletableFuture<Result> ra=convex.transact(a);
		CompletableFuture<Result> rb=convex.transact(b);
		convex.preCompile(b).complete(Result.value(b));
		assertTrue(convex.sent.isEmpty(),"A later transaction waits for an earlier one still compiling");

		convex.preCompile(a).complete(Result.value(a));
		assertEquals(CVMLong.create(42),ra.get(WAIT,TimeUnit.MILLISECONDS).getValue());
		assertEquals(CVMLong.create(43),rb.get(WAIT,TimeUnit.MILLISECONDS).getValue());
		assertEquals(List.of(a,b),convex.sentCommands());
	}

	@Test
	public void testCompileErrorIsTheResultAndConsumesNoSequence() throws Exception {
		HeldConvex convex=new HeldConvex();
		convex.setPreCompile(true);
		convex.setNextSequence(42);
		ACell bad=Reader.read("(bad)");
		ACell good=Reader.read("(good)");

		CompletableFuture<Result> rBad=convex.transact(bad);
		CompletableFuture<Result> rGood=convex.transact(good);
		Result compileError=Result.error(ErrorCodes.COMPILE,"Expected failure");
		convex.preCompile(bad).complete(compileError);
		convex.preCompile(good).complete(Result.value(good));

		assertEquals(compileError,rBad.get(WAIT,TimeUnit.MILLISECONDS));
		assertEquals(CVMLong.create(42),rGood.get(WAIT,TimeUnit.MILLISECONDS).getValue());
	}

	@Test
	public void testFailedLookupFailsOnlyItsTransaction() throws Exception {
		HeldConvex convex=new HeldConvex();

		CompletableFuture<Result> first=convex.transact(transaction(convex));
		CompletableFuture<Result> second=convex.transact(transaction(convex));
		convex.nextLookup().reply().complete(Result.error(ErrorCodes.TIMEOUT,"No reply"));
		assertEquals(ErrorCodes.TIMEOUT,first.get(WAIT,TimeUnit.MILLISECONDS).getErrorCode());

		convex.nextLookup().reply().complete(sequence(41));
		assertEquals(CVMLong.create(42),second.get(WAIT,TimeUnit.MILLISECONDS).getValue());
		assertEquals(List.of(42L),convex.sentSequences());
	}

	@Test
	public void testLookupKeepsReservationMadeMeanwhile() throws Exception {
		HeldConvex convex=new HeldConvex();

		CompletableFuture<Result> result=convex.transact(transaction(convex));
		Lookup lookup=convex.nextLookup();
		convex.setNextSequence(51);
		lookup.reply().complete(sequence(41));

		assertEquals(CVMLong.create(51),result.get(WAIT,TimeUnit.MILLISECONDS).getValue());
	}

	@Test
	public void testCancelWithdrawsQueuedTransaction() throws Exception {
		HeldConvex convex=new HeldConvex();

		CompletableFuture<Result> cancelled=convex.transact(transaction(convex));
		Lookup lookup=convex.nextLookup();
		CompletableFuture<Result> next=convex.transact(transaction(convex));
		cancelled.cancel(true);
		lookup.reply().complete(sequence(41));

		assertEquals(CVMLong.create(42),next.get(WAIT,TimeUnit.MILLISECONDS).getValue());
		assertEquals(List.of(42L),convex.sentSequences());
		assertTrue(convex.lookups.isEmpty(),"A withdrawn transaction's lookup still serves the next one");
	}

	@Test
	public void testOtherOriginUsesItsOwnNextSequence() throws Exception {
		HeldConvex convex=new HeldConvex();
		convex.setNextSequence(42);
		Address other=Address.create(5678);

		CompletableFuture<Result> result=convex.transact(
				Invoke.create(other,ATransaction.UNKNOWN_SEQUENCE,Reader.read("*address*")));
		Lookup lookup=convex.nextLookup();
		assertEquals(other,lookup.origin());
		lookup.reply().complete(sequence(7));

		assertEquals(CVMLong.create(8),result.get(WAIT,TimeUnit.MILLISECONDS).getValue());
		assertEquals(CVMLong.create(42),convex.transact(transaction(convex)).get(WAIT,TimeUnit.MILLISECONDS).getValue(),
				"The client's own sequence is unaffected");
	}

	@Test
	public void testMissingKeyPairConsumesNoSequence() throws Exception {
		HeldConvex convex=new HeldConvex();
		convex.setNextSequence(42);
		AKeyPair kp=convex.getKeyPair();

		convex.setKeyPair(null);
		assertEquals(ErrorCodes.SIGNATURE,convex.transact(transaction(convex)).get(WAIT,TimeUnit.MILLISECONDS).getErrorCode());

		convex.setKeyPair(kp);
		assertEquals(CVMLong.create(42),convex.transact(transaction(convex)).get(WAIT,TimeUnit.MILLISECONDS).getValue());
	}

	@Test
	public void testPrepareGivesUpOnUnansweredCompile() throws Exception {
		HeldConvex convex=new HeldConvex();
		convex.setPreCompile(true);
		convex.setNextSequence(42);
		convex.setTimeout(100);
		ACell code=Reader.read("(a)");

		ResultException e=assertThrows(ResultException.class,()->convex.prepareTransaction(code));
		assertEquals(ErrorCodes.TIMEOUT,e.getResult().getErrorCode());

		convex.preCompile(code).complete(Result.value(code));
		assertEquals(CVMLong.create(42),convex.transact(transaction(convex)).get(WAIT,TimeUnit.MILLISECONDS).getValue(),
				"The abandoned transaction consumes no sequence");
	}

	@Test
	public void testCallerHoldingMonitorSubmitsAtOnce() throws Exception {
		HeldConvex convex=new HeldConvex();

		CompletableFuture<Result> queued=convex.transact(transaction(convex));
		Lookup lookup=convex.nextLookup();
		synchronized (convex) {
			convex.setNextSequence(51);
			CompletableFuture<Result> locked=convex.transact(transaction(convex));
			assertTrue(locked.isDone(),"A caller holding the monitor is not queued behind work that needs it");
			assertEquals(CVMLong.create(51),locked.get().getValue());
		}
		lookup.reply().complete(sequence(41));

		assertEquals(CVMLong.create(52),queued.get(WAIT,TimeUnit.MILLISECONDS).getValue());
		assertEquals(List.of(51L,52L),convex.sentSequences());
	}

	private static ATransaction transaction(Convex convex) {
		return Invoke.create(convex.getAddress(),ATransaction.UNKNOWN_SEQUENCE,Reader.read("*address*"));
	}

	private static Result sequence(long seq) {
		return Result.value(CVMLong.create(seq));
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

	/** A sequence lookup awaiting its reply from the test. */
	private record Lookup(Address origin, CompletableFuture<Result> reply) {}

	/**
	 * A client whose sequence lookups and compiles are answered by the test. It
	 * records each submitted transaction, answering it with its sequence number.
	 */
	private static final class HeldConvex extends ConvexDirect {
		private final BlockingQueue<Lookup> lookups=new LinkedBlockingQueue<>();
		private final Map<ACell,CompletableFuture<Result>> compiles=new ConcurrentHashMap<>();
		private final List<SignedData<ATransaction>> sent=Collections.synchronizedList(new ArrayList<>());

		private HeldConvex() {
			super(Address.create(1234),AKeyPair.createSeeded(1234),null);
		}

		@Override
		public CompletableFuture<Result> query(ACell query, Address address) {
			CompletableFuture<Result> reply=new CompletableFuture<>();
			lookups.add(new Lookup(address,reply));
			return reply;
		}

		@Override
		public CompletableFuture<Result> preCompile(ACell code) {
			return compiles.computeIfAbsent(code,c->new CompletableFuture<>());
		}

		@Override
		public CompletableFuture<Result> transact(SignedData<ATransaction> signed) {
			sent.add(signed);
			return CompletableFuture.completedFuture(sequence(signed.getValue().getSequence()));
		}

		private Lookup nextLookup() throws InterruptedException {
			Lookup lookup=lookups.poll(WAIT,TimeUnit.MILLISECONDS);
			assertNotNull(lookup,"Expected a sequence lookup");
			return lookup;
		}

		private List<Long> sentSequences() {
			synchronized (sent) {
				return sent.stream().map(s->s.getValue().getSequence()).toList();
			}
		}

		private List<ACell> sentCommands() {
			synchronized (sent) {
				return sent.stream().map(s->((Invoke)s.getValue()).getCommand()).toList();
			}
		}
	}
}
