package convex.peer;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;

import convex.core.util.Utils;

/**
 * Handles the failures that reach the top of a peer loop: failures of the peer's own
 * work, as requests are contained below. The loop drops that iteration and carries on,
 * unless the JVM itself has failed, when the peer shuts down. Each kind of failure is
 * logged the first time, out of memory at warn and anything else at error, and later
 * ones of the same kind at debug.
 */
final class LoopFailures {
	private final Logger log;
	private final Server server;
	private final String loopName;
	private final Set<Class<?>> logged=ConcurrentHashMap.newKeySet();

	/**
	 * Creates the failure handling of one loop.
	 *
	 * @param log Logger of the loop
	 * @param server Server the loop belongs to
	 * @param loopName Name of the loop, for the log
	 */
	LoopFailures(Logger log, Server server, String loopName) {
		this.log=log;
		this.server=server;
		this.loopName=loopName;
	}

	/**
	 * Handles one failure that reached the top of the loop.
	 *
	 * @param e Failure of one iteration
	 * @return True if the loop may carry on, false if it must stop
	 */
	boolean handle(Throwable e) {
		if (Utils.isJvmFailure(e)) {
			// Nothing this peer holds can be trusted: no final checkpoint
			server.requestShutdown("JVM failure in "+loopName,e,false);
			return false;
		}
		// Only the first of each kind above debug, so a recurring failure cannot flood the log
		if (!logged.add(e.getClass())) {
			log.debug("Iteration of {} failed",loopName,e);
		} else if (e instanceof OutOfMemoryError) {
			log.warn("Iteration of {} ran out of memory (logged at warn the first time only)",loopName,e);
		} else {
			log.error("Iteration of {} failed (logged at error the first time only)",loopName,e);
		}
		return true;
	}
}
