package convex.net;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;

import convex.core.ErrorCodes;
import convex.core.Result;
import convex.core.SourceCodes;
import convex.core.data.Strings;
import convex.core.exceptions.BadSignatureException;
import convex.core.exceptions.MissingDataException;
import convex.core.exceptions.ResultException;
import convex.core.exceptions.ValidationException;
import convex.core.util.Utils;

/**
 * The failures of requests that one handling site contains: how each is logged, and
 * the reply to it. Running out of memory, and a failure that is probably a defect
 * rather than bad input (an {@link Error} other than {@link StackOverflowError} or
 * {@link OutOfMemoryError}), are each logged at warn with the stack trace the first
 * time; anything else, and later ones, at debug.
 */
public final class RequestFailures {
	private static final Result ERR_FORMAT=Result.error(ErrorCodes.FORMAT,Strings.BAD_FORMAT).withSource(SourceCodes.PEER);
	private static final Result ERR_SIGNATURE=Result.error(ErrorCodes.SIGNATURE,Strings.BAD_SIGNATURE).withSource(SourceCodes.PEER);
	private static final Result ERR_MISSING=Result.error(ErrorCodes.MISSING,Strings.intern("Missing data")).withSource(SourceCodes.PEER);
	private static final Result ERR_STACK=Result.error(ErrorCodes.LIMIT,Strings.intern("Request exceeded this peer's stack depth")).withSource(SourceCodes.PEER);
	private static final Result ERR_PEER=Result.error(ErrorCodes.PEER,Strings.intern("Peer failed to process request")).withSource(SourceCodes.PEER);

	private final Logger log;
	private final AtomicBoolean warnedMemory=new AtomicBoolean();
	private final AtomicBoolean warnedDefect=new AtomicBoolean();

	/**
	 * Creates the failures of one handling site.
	 *
	 * @param log Logger of the handling site
	 */
	public RequestFailures(Logger log) {
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

	/**
	 * Gives the reply to a request that failed. A typed failure keeps its meaning: a
	 * signature that does not verify is SIGNATURE, any other invalid data FORMAT,
	 * missing data MISSING, and a {@link ResultException} its own result. Any other
	 * failure is this peer's: running out of memory is LOAD, a stack overflow LIMIT,
	 * and anything else PEER.
	 *
	 * @param e Failure of the request
	 * @return Error result to reply with
	 */
	public static Result reply(Throwable e) {
		if (e instanceof ResultException re) return re.getResult();
		if (e instanceof BadSignatureException) return ERR_SIGNATURE;
		if (e instanceof ValidationException) return ERR_FORMAT;
		if (e instanceof MissingDataException) return ERR_MISSING;
		// The heap ran out, which may be this peer's load rather than the request: a retry may succeed
		if (e instanceof OutOfMemoryError) return Result.SERVER_LOADED;
		// Too deep for this peer: the same request would fail again
		if (e instanceof StackOverflowError) return ERR_STACK;
		return ERR_PEER;
	}

	private AtomicBoolean warnedFlag(Throwable e) {
		if (e instanceof OutOfMemoryError) return warnedMemory;
		if (Utils.isProbableDefect(e)) return warnedDefect;
		return null;
	}
}
