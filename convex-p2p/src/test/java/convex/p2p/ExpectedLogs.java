package convex.p2p;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;

/**
 * Log records that a test causes on purpose. While a scope is open, each record at warn
 * or above that carries one of the objects the test created to cause it, as the
 * record's throwable or one of its causes, or as a message argument, is kept instead
 * of being written; every other record is written as usual. Closing fails if the scope
 * kept no record.
 *
 * <p>A trimmed copy of {@code convex.peer.ExpectedLogs} in convex-peer's tests, which
 * is the official version: it holds only matching by carried object. Check it against
 * that version and keep the two in step.</p>
 */
public final class ExpectedLogs implements AutoCloseable {

	private static final List<ExpectedLogs> OPEN=new CopyOnWriteArrayList<>();

	/** Keeps, instead of writing, each record at warn or above that an open scope matches. */
	private static final class Capture extends TurboFilter {
		private static final Capture INSTANCE=new Capture();

		/** Adds the filter to the logging context, unless it is there already. */
		static synchronized void register(LoggerContext context) {
			// Added again if the logging configuration was reset since
			if (!context.getTurboFilterList().contains(INSTANCE)) {
				INSTANCE.start();
				context.addTurboFilter(INSTANCE);
			}
		}

		@Override
		public FilterReply decide(Marker marker, Logger logger, Level level, String format, Object[] params, Throwable t) {
			if ((level==null)||!level.isGreaterOrEqual(Level.WARN)) return FilterReply.NEUTRAL;
			for (ExpectedLogs scope: OPEN) {
				if (scope.matches(params,t)) {
					scope.kept.add(level);
					return FilterReply.DENY;
				}
			}
			return FilterReply.NEUTRAL;
		}
	}

	private final Set<Object> carried=Collections.newSetFromMap(new IdentityHashMap<>());
	private final List<Level> kept=Collections.synchronizedList(new ArrayList<>());

	private ExpectedLogs(List<Object> carried) {
		this.carried.addAll(carried);
	}

	/**
	 * Opens a scope matching records that carry any of the given objects.
	 *
	 * @param objects Objects the test creates to cause the records
	 * @return Open scope
	 * @throws IllegalStateException if tests do not log through logback
	 */
	public static ExpectedLogs carrying(Object... objects) {
		ILoggerFactory factory;
		// SLF4J initialises holding its class's lock, and meanwhile gives other threads a
		// substitute factory: wait for whichever thread began it to finish
		synchronized (LoggerFactory.class) {
			factory=LoggerFactory.getILoggerFactory();
		}
		if (!(factory instanceof LoggerContext context)) {
			throw new IllegalStateException("Tests must log through logback to capture records, not "+factory.getClass().getName());
		}
		ExpectedLogs scope=new ExpectedLogs(List.of(objects));
		Capture.register(context);
		OPEN.add(scope);
		return scope;
	}

	/**
	 * Gets the levels of the records kept so far.
	 *
	 * @return Levels of the records kept, in order
	 */
	public List<Level> levels() {
		synchronized (kept) {
			return List.copyOf(kept);
		}
	}

	/**
	 * Closes this scope.
	 *
	 * @throws AssertionError if the scope kept no record
	 */
	@Override
	public void close() {
		OPEN.remove(this);
		if (kept.isEmpty()) throw new AssertionError("Expected a log record that was not logged");
	}

	private boolean matches(Object[] params, Throwable t) {
		if (carries(t)) return true;
		if (params!=null) {
			for (Object p: params) {
				if (carries(p)) return true;
			}
		}
		return false;
	}

	private boolean carries(Object o) {
		if (o==null) return false;
		if (o instanceof Throwable t) {
			for (Throwable c=t; c!=null; c=c.getCause()) {
				if (carried.contains(c)) return true;
			}
			return false;
		}
		return carried.contains(o);
	}
}
