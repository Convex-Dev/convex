package convex.lattice.kv;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Index;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.lattice.cursor.Root;

@Timeout(10)
class KVConcurrencyTest {

	/** Both writers attempt an update of the same snapshot, forcing a real CAS conflict. */
	private static final class RacingRoot extends Root<Index<AString,AVector<ACell>>> {
		private final CountDownLatch writers=new CountDownLatch(2);
		boolean racing;

		RacingRoot() { super(Index.none()); }

		@Override public Index<AString,AVector<ACell>> updateAndGet(UnaryOperator<Index<AString,AVector<ACell>>> action) {
			AtomicBoolean first=new AtomicBoolean(racing);
			return super.updateAndGet(value -> {
				if (first.getAndSet(false)) {
					writers.countDown();
					try { assertTrue(writers.await(5,TimeUnit.SECONDS)); }
					catch (InterruptedException e) { throw new AssertionError(e); }
				}
				return action.apply(value);
			});
		}
	}

	private static <T> List<T> race(RacingRoot root,Callable<T> first,Callable<T> second) throws Exception {
		root.racing=true;
		try (var executor=Executors.newFixedThreadPool(2)) {
			var a=executor.submit(first);
			var b=executor.submit(second);
			return java.util.Arrays.asList(a.get(5,TimeUnit.SECONDS),b.get(5,TimeUnit.SECONDS));
		} finally { root.racing=false; }
	}

	@Test void incrementsAndReturnValuesSurviveContention() throws Exception {
		var root=new RacingRoot();
		var kv=new LatticeKV(root,Strings.create("replica"),() -> CVMLong.ONE);
		kv.incrby("counter",0);
		var results=race(root,() -> kv.incr("counter"),() -> kv.incr("counter"));
		assertTrue(results.containsAll(List.of(1L,2L)));
		assertEquals(2,KVCounter.getValue(kv.get("counter")));
	}

	@Test void independentHashEditsSurviveWithOneClockReadEach() throws Exception {
		var root=new RacingRoot();
		var reads=new AtomicInteger();
		var kv=new LatticeKV(root,Strings.create("replica"),() -> CVMLong.create(reads.incrementAndGet()));
		race(root,() -> { kv.hset("hash","a",CVMLong.ONE); return true; },
			() -> { kv.hset("hash","b",CVMLong.ONE); return true; });
		assertEquals(2,reads.get(),"Retries must reuse the mutation timestamp");
		assertEquals(CVMLong.ONE,kv.hget("hash","a"));
		assertEquals(CVMLong.ONE,kv.hget("hash","b"));
	}

	@Test void listItemIsConsumedOnlyOnce() throws Exception {
		var root=new RacingRoot();
		var kv=new LatticeKV(root,Strings.create("replica"),() -> CVMLong.ONE);
		kv.rpush("list",Strings.create("only"));
		var results=race(root,() -> kv.lpop("list"),() -> kv.lpop("list"));
		assertEquals(1,results.stream().filter(Strings.create("only")::equals).count());
		assertEquals(1,results.stream().filter(java.util.Objects::isNull).count());
		assertEquals(0,kv.llen("list"));
	}

	@Test void duplicateSetAddsReportOnlyOneAddition() throws Exception {
		var root=new RacingRoot();
		var kv=new LatticeKV(root,Strings.create("replica"),() -> CVMLong.ONE);
		var results=race(root,() -> kv.sadd("set",Strings.create("member")),
			() -> kv.sadd("set",Strings.create("member")));
		assertEquals(1,results.stream().mapToLong(Long::longValue).sum());
		assertEquals(1,kv.scard("set"));
	}
}
