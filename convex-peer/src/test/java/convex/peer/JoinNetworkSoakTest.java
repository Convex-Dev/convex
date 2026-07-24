package convex.peer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Soak harness for peer join/sync, for hunting low-rate intermittent failures.
 *
 * <p>Not part of the normal build. Enable explicitly:</p>
 *
 * <pre>
 * mvn -B test -pl convex-peer -Dtest=JoinNetworkSoakTest -Dconvex.soak=true
 * mvn -B test -pl convex-peer -Dtest=JoinNetworkSoakTest -Dconvex.soak=true -Dconvex.soak.runs=10000
 * </pre>
 *
 * <p>Why this exists: the join failure this was written for reproduced roughly once in
 * 400 runs, and only under CPU contention. One test per Maven invocation takes ~20s, so
 * ~75 attempts took half an hour and found nothing. Repeating inside a single JVM does
 * 3000 in under three minutes, which turned an unreproducible CI report into a
 * diagnosable failure. Run it alongside other load — an otherwise idle machine may not
 * reproduce a scheduling-sensitive fault at all.</p>
 *
 * <p>When a run fails, widen the assertion message rather than the iteration count: the
 * failure that motivated this was only understood once the message carried the peer's own
 * finality point and the source's advertised position, which together showed the source
 * had regressed to genesis and the joining peer was correct all along.</p>
 */
@EnabledIfSystemProperty(named="convex.soak", matches="true")
public class JoinNetworkSoakTest {

	/** Iterations, overridable with -Dconvex.soak.runs. */
	private static final int RUNS=Integer.getInteger("convex.soak.runs",3000);

	@Test
	public void soakJoinReplaysInsteadOfAdoptingRemoteState() throws Exception {
		JoinNetworkTest delegate=new JoinNetworkTest();
		for (int i=0; i<RUNS; i++) {
			try {
				delegate.testJoinReplaysInsteadOfAdoptingRemoteState();
			} catch (Throwable t) {
				throw new AssertionError("Soak failed on iteration "+i+" of "+RUNS+": "+t,t);
			}
		}
	}
}
