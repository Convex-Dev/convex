package convex.net.ingress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * The scheduler on its own: which connection is served next, by lane, credit and
 * worker limit.
 */
public class FairSchedulerTest {

	private static IngressChannel channel() {
		return new IngressChannel(null, null, null, null);
	}

	@Test
	public void testRoundRobinWithinLane() {
		FairScheduler s = new FairScheduler(2, 1000, 100);
		IngressChannel a = channel(), b = channel(), c = channel();
		s.enqueue(a, false);
		s.enqueue(b, false);
		s.enqueue(c, false);
		List<IngressChannel> order = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			IngressChannel next = s.next();
			order.add(next);
			next.credit -= 100; // served one quantum
			s.finish(next);
			s.enqueue(next, false);
		}
		assertEquals(List.of(a, b, c, a, b, c), order);
	}

	@Test
	public void testOverrunRepaidBeforeServedAgain() {
		FairScheduler s = new FairScheduler(2, 1000, 100);
		IngressChannel big = channel(), small = channel();
		s.enqueue(big, false);
		s.enqueue(small, false);

		// big's first message overruns its quantum tenfold: it is still served at once
		IngressChannel first = s.next();
		assertSame(big, first);
		big.credit -= 1000;
		s.finish(big);
		s.enqueue(big, false);

		int smallTurns = 0;
		while (true) {
			IngressChannel next = s.next();
			s.finish(next);
			if (next == big) break;
			smallTurns++;
			next.credit -= 100;
			s.enqueue(next, false);
		}
		// big ends 900 in debt and gains 100 a round: positive again at its tenth
		// round, small being served once in each
		assertEquals(10, smallTurns);
	}

	@Test
	public void testDebtorAloneIsServedWithoutWaitingRounds() {
		FairScheduler s = new FairScheduler(1, 1000, 100);
		IngressChannel debtor = channel();
		debtor.credit = -1_000_000;
		s.enqueue(debtor, false);
		assertSame(debtor, s.next());
		assertTrue(debtor.credit > 0);
		assertTrue(debtor.credit <= 100);
	}

	@Test
	public void testLanesAlternate() {
		FairScheduler s = new FairScheduler(2, 1000, 100);
		IngressChannel p = channel(), o = channel();
		s.enqueue(p, true);
		s.enqueue(o, false);
		List<IngressChannel> order = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			IngressChannel next = s.next();
			order.add(next);
			next.credit = 0;
			s.finish(next);
			s.enqueue(next, next == p);
		}
		assertEquals(List.of(p, o, p, o), order);
	}

	@Test
	public void testOrdinaryLaneLimitedToItsWorkers() {
		FairScheduler s = new FairScheduler(2, 1000, 100);
		IngressChannel o1 = channel(), o2 = channel(), o3 = channel(), p = channel();
		s.enqueue(o1, false);
		s.enqueue(o2, false);
		s.enqueue(o3, false);
		IngressChannel a = s.next();
		IngressChannel b = s.next();
		assertEquals(2, s.ordinaryServing());
		assertFalse(s.hasEligible(), "a third ordinary turn waits for a worker of its own lane");
		assertNull(s.next());

		s.enqueue(p, true);
		assertTrue(s.hasEligible(), "the reserved worker serves the priority lane");
		assertSame(p, s.next());

		s.finish(a);
		assertSame(o3, s.next());
		assertEquals(2, s.ordinaryServing());
		s.finish(b);
		assertEquals(1, s.ordinaryServing());
	}

	/**
	 * A connection that joins a lane is served before any connection ahead of it has a
	 * second turn: at most one turn of each of them.
	 */
	@Test
	public void testJoinerServedWithinOneRound() {
		FairScheduler s = new FairScheduler(2, 1000, 100);
		List<IngressChannel> busy = new ArrayList<>();
		for (int i = 0; i < 100; i++) {
			IngressChannel c = channel();
			busy.add(c);
			s.enqueue(c, false);
		}
		IngressChannel joiner = channel();
		s.enqueue(joiner, false);
		Set<IngressChannel> served = new HashSet<>();
		while (true) {
			IngressChannel next = s.next();
			s.finish(next);
			if (next == joiner) break;
			assertTrue(served.add(next), "no connection has a second turn before the joiner");
			next.credit -= 150; // overrun by up to a message
			s.enqueue(next, false);
		}
		assertEquals(100, served.size());
	}
}
