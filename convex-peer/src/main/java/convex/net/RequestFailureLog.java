package convex.net;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;

/**
 * Logs the failures of requests that one handling site contains. Running out of
 * memory, and a failure that is probably a defect rather than bad input (an
 * {@link Error} other than {@link StackOverflowError} or {@link OutOfMemoryError}),
 * are each logged at warn with the stack trace the first time; anything else, and
 * later ones, at debug.
 */
public final class RequestFailureLog {
	private final Logger log;
	private final AtomicBoolean warnedMemory=new AtomicBoolean();
	private final AtomicBoolean warnedDefect=new AtomicBoolean();

	/**
	 * Creates a log for one handling site.
	 *
	 * @param log Logger of the handling site
	 */
	public RequestFailureLog(Logger log) {
		this.log=log;
	}

	/**
	 * Logs one contained request failure.
	 *
	 * @param e Failure of the request
	 * @param format Message in SLF4J format, describing the request
	 * @param args Message arguments
	 */
	public void log(Throwable e, String format, Object... args) {
		Object[] withCause=Arrays.copyOf(args,args.length+1);
		withCause[args.length]=e;
		AtomicBoolean warned=warnedFlag(e);
		if ((warned!=null)&&warned.compareAndSet(false,true)) {
			log.warn(format+" (logged at warn the first time only)",withCause);
		} else {
			log.debug(format,withCause);
		}
	}

	private AtomicBoolean warnedFlag(Throwable e) {
		if (e instanceof OutOfMemoryError) return warnedMemory;
		if ((e instanceof Error)&&!(e instanceof StackOverflowError)) return warnedDefect;
		return null;
	}
}
