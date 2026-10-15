package convex.core.cvm;

import static convex.core.lang.ACVMTest.compile;
import static convex.core.lang.ACVMTest.step;
import static convex.test.Assertions.assertNotError;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import convex.core.cpos.CPoSConstants;
import convex.core.data.AVector;
import convex.core.data.impl.LongBlob;
import convex.core.data.Symbol;
import convex.core.data.prim.CVMLong;
import convex.core.init.InitTest;

/** Execution limits, queue retention and FIFO ordering for due operations. */
public class ScheduledTransactionsTest {
	private static final Symbol EXECUTED=Symbol.create("executed");
	private static final int LIMIT=(int)CPoSConstants.MAX_SCHEDULED_TRANSACTIONS_PER_BLOCK;

	@ParameterizedTest
	@CsvSource({"0,0", "0,1", "0,99", "0,100", "0,101", "0,201",
		"1,0", "1,1", "1,99", "1,100", "1,101", "1,201"})
	public void testSingleTimestampBucket(long version,int count) {
		State state=initialState(version);
		long now=state.getTimestamp().longValue();
		state=schedule(state,now,0,count);
		var original=state.getSchedule().get(LongBlob.create(now));
		if (count==0) assertSame(state,state.applyScheduledTransactions());
		for (int completed=0;completed<count;) {
			state=state.applyScheduledTransactions();
			completed=Math.min(count,completed+LIMIT);
			assertExecuted(state,completed);
			var remaining=state.getSchedule().get(LongBlob.create(now));
			if (completed==count) {
				assertNull(remaining);
			} else {
				assertEquals(original.slice(completed,count),remaining);
			}
		}
		assertTrue(state.getSchedule().isEmpty());
		assertSame(state,state.applyScheduledTransactions());
	}

	@ParameterizedTest
	@ValueSource(longs = {0, 1})
	public void testLimitAcrossBucketsAndFutureWork(long version) {
		State state=initialState(version);
		long now=state.getTimestamp().longValue();
		int first=LIMIT/2+1;
		int total=LIMIT+first;
		state=schedule(state,now-1,0,first);
		state=schedule(state,now,first,total);
		state=schedule(state,now+1,total,total+1);
		var due=state.getSchedule().get(LongBlob.create(now));
		var future=state.getSchedule().get(LongBlob.create(now+1));

		state=state.applyScheduledTransactions();
		assertExecuted(state,LIMIT);
		assertNull(state.getSchedule().get(LongBlob.create(now-1)));
		assertEquals(due.slice(LIMIT-first,due.count()),state.getSchedule().get(LongBlob.create(now)));
		assertEquals(future,state.getSchedule().get(LongBlob.create(now+1)));

		state=state.applyScheduledTransactions();
		assertExecuted(state,total);
		assertEquals(1,state.getSchedule().count());
		assertSame(state,state.applyScheduledTransactions());
		state=state.withTimestamp(now+1).applyScheduledTransactions();
		assertExecuted(state,total+1);
		assertTrue(state.getSchedule().isEmpty());
	}

	private static State initialState(long version) {
		var state=Migrations.applyTo(InitTest.STATE,version);
		var context=step(Context.create(state,InitTest.HERO),"(def executed [])");
		assertNotError(context);
		return context.getState();
	}

	private static State schedule(State state,long time,int from,int to) {
		var context=Context.create(state,InitTest.HERO);
		for (int i=from;i<to;i++) {
			var op=compile(context,"(def executed (conj executed "+i+"))");
			state=state.scheduleOp(time,InitTest.HERO,op);
		}
		return state;
	}

	private static void assertExecuted(State state,int count) {
		AVector<?> executed=(AVector<?>)state.getAccount(InitTest.HERO).getEnvironment().get(EXECUTED);
		assertEquals(count,executed.count());
		for (int i=0;i<count;i++) assertEquals(CVMLong.create(i),executed.get(i));
	}
}
