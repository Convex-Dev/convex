package convex.peer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.event.Level;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;

/**
 * Log records that a test causes on purpose. While a scope is open, each record at warn
 * or above that it matches is kept for the test's assertions instead of being written;
 * every other record is written as usual. A scope matches a record that carries one of
 * the objects the test created to cause it, as the record's throwable, among that
 * throwable's causes and suppressed throwables, or as a message argument; or a record
 * of the logger given to it, logged on one of the threads given to it. If several open
 * scopes match a record, the one opened first keeps it.
 *
 * <p>Open a scope before starting what logs and close it after stopping that, since a
 * component's threads log after the test's own work returns. Closing fails if the
 * scope kept no record. A test class whose tests share the objects they inject
 * registers one scope for the class with {@code @RegisterExtension}.</p>
 */
public final class ExpectedLogs implements AutoCloseable, BeforeAllCallback, AfterAllCallback {

	/**
	 * A kept record
	 * @param level Level it was logged at
	 * @param logger Name of the logger
	 * @param thrown Throwable logged with it, or null
	 */
	public record Entry(Level level, String logger, Throwable thrown) {}

	private static final List<ExpectedLogs> OPEN=new CopyOnWriteArrayList<>();

	static {
		// Before Capture, which extends a logback class, is loaded: tests patched into
		// convex.peer run in that module, which does not read logback's modules. Only
		// Capture names Capture as a TurboFilter, so verifying this class does not load it.
		Module self=ExpectedLogs.class.getModule();
		ModuleLayer layer=self.getLayer();
		if (layer!=null) {
			for (String name: List.of("ch.qos.logback.classic","ch.qos.logback.core")) {
				layer.findModule(name).ifPresent(self::addReads);
			}
		}
	}

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
		public FilterReply decide(Marker marker, Logger logger, ch.qos.logback.classic.Level level, String format, Object[] params, Throwable t) {
			if ((level==null)||!level.isGreaterOrEqual(ch.qos.logback.classic.Level.WARN)) return FilterReply.NEUTRAL;
			for (ExpectedLogs scope: OPEN) {
				if (scope.matches(logger.getName(),params,t)) {
					Level slf4jLevel=Level.intToLevel(ch.qos.logback.classic.Level.toLocationAwareLoggerInteger(level));
					scope.keep(slf4jLevel,logger.getName(),thrown(params,t));
					return FilterReply.DENY;
				}
			}
			return FilterReply.NEUTRAL;
		}
	}

	private final Set<Object> carried=Collections.newSetFromMap(new IdentityHashMap<>());
	private final String threadLogger;
	private final Set<Thread> threads=Collections.newSetFromMap(new IdentityHashMap<>());
	private final List<Entry> kept=Collections.synchronizedList(new ArrayList<>());
	private final boolean required;

	private ExpectedLogs(Collection<?> carried, String threadLogger, Collection<Thread> threads, boolean required) {
		this.carried.addAll(carried);
		this.threadLogger=threadLogger;
		this.threads.addAll(threads);
		this.required=required;
	}

	/**
	 * Creates a scope, not yet open, matching records that carry any of the given
	 * objects. A Collection given is taken as its elements.
	 *
	 * @param objects Objects the test creates to cause the records
	 * @return New scope, to open with {@link #open()} or register as an extension
	 */
	public static ExpectedLogs carrying(Object... objects) {
		ArrayList<Object> all=new ArrayList<>();
		for (Object o: objects) {
			if (o instanceof Collection<?> c) all.addAll(c); else all.add(o);
		}
		return new ExpectedLogs(all,null,List.of(),true);
	}

	/**
	 * Creates a scope, not yet open, matching the records of one logger logged on any of
	 * the given threads: the test's own, or those of a component it started.
	 *
	 * @param logger Name of the logger
	 * @param threads Threads whose records the test expects
	 * @return New scope, to open with {@link #open()}
	 */
	public static ExpectedLogs onThreads(String logger, Thread... threads) {
		return new ExpectedLogs(List.of(),logger,List.of(threads),true);
	}

	/**
	 * Gets a scope like this one that closes without failing if it kept no record, for a
	 * site that logs above debug only once in the JVM, which an earlier test may have used.
	 *
	 * @return New scope, not yet open
	 */
	public ExpectedLogs ifLogged() {
		return new ExpectedLogs(carried,threadLogger,threads,false);
	}

	/**
	 * Opens this scope.
	 *
	 * @return This scope
	 * @throws IllegalStateException if tests do not log through logback
	 */
	public ExpectedLogs open() {
		ILoggerFactory factory;
		// SLF4J initialises holding its class's lock, and meanwhile gives other threads a
		// substitute factory: wait for whichever thread began it to finish
		synchronized (LoggerFactory.class) {
			factory=LoggerFactory.getILoggerFactory();
		}
		if (!(factory instanceof LoggerContext context)) {
			throw new IllegalStateException("Tests must log through logback to capture records, not "+factory.getClass().getName());
		}
		Capture.register(context);
		OPEN.add(this);
		return this;
	}

	/**
	 * Closes this scope.
	 *
	 * @throws AssertionError if the scope kept no record, unless made with {@link #ifLogged()}
	 */
	@Override
	public void close() {
		OPEN.remove(this);
		if (required&&kept.isEmpty()) throw new AssertionError("Expected a log record that was not logged");
	}

	/**
	 * Gets the records kept so far.
	 *
	 * @return Records kept, in order
	 */
	public List<Entry> records() {
		synchronized (kept) {
			return List.copyOf(kept);
		}
	}

	/**
	 * Asserts this scope kept a record at the given level carrying the given throwable.
	 *
	 * @param level Level expected
	 * @param thrown Throwable the record carries, as its own throwable or among its causes
	 * @throws AssertionError if no such record was kept
	 */
	public void assertLogged(Level level, Throwable thrown) {
		for (Entry e: records()) {
			if ((e.level()==level)&&causes(e.thrown()).contains(thrown)) return;
		}
		throw new AssertionError("No record at "+level+" carrying "+thrown+" among "+records());
	}

	@Override
	public void beforeAll(ExtensionContext context) {
		open();
	}

	@Override
	public void afterAll(ExtensionContext context) {
		close();
	}

	private boolean matches(String logger, Object[] params, Throwable t) {
		if (logger.equals(threadLogger)&&threads.contains(Thread.currentThread())) return true;
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
			for (Throwable c: causes(t)) {
				if (carried.contains(c)) return true;
			}
			return false;
		}
		return carried.contains(o);
	}

	private void keep(Level level, String logger, Throwable thrown) {
		kept.add(new Entry(level,logger,thrown));
	}

	/** The throwable of a record: given separately, or as its last argument. */
	private static Throwable thrown(Object[] params, Throwable t) {
		if (t!=null) return t;
		if ((params!=null)&&(params.length>0)&&(params[params.length-1] instanceof Throwable last)) return last;
		return null;
	}

	/** A throwable, its causes and its suppressed throwables, each once. */
	private static Set<Throwable> causes(Throwable t) {
		Set<Throwable> seen=Collections.newSetFromMap(new IdentityHashMap<>());
		ArrayList<Throwable> pending=new ArrayList<>();
		if (t!=null) pending.add(t);
		while (!pending.isEmpty()) {
			Throwable e=pending.remove(pending.size()-1);
			if (!seen.add(e)) continue;
			if (e.getCause()!=null) pending.add(e.getCause());
			Collections.addAll(pending,e.getSuppressed());
		}
		return seen;
	}
}
