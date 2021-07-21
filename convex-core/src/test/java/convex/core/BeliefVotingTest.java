package convex.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import convex.core.data.Maps;

public class BeliefVotingTest {

	@Test
	public void testComputeVote() {
		assertEquals(100.0, Belief.computeVote(Maps.hashMapOf(1, 50.0, 0, 50.0)), 0.000001);
		assertEquals(0.0, Belief.computeVote(Maps.hashMapOf()), 0.000001);
	}
}
