package convex.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.slf4j.Marker;
import org.slf4j.event.Level;
import org.slf4j.helpers.LegacyAbstractLogger;

import convex.core.ErrorCodes;
import convex.core.Result;
import convex.core.cvm.Keywords;
import convex.core.data.Hash;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.BadSignatureException;
import convex.core.exceptions.InvalidDataException;
import convex.core.exceptions.MisbehaviourException;
import convex.core.exceptions.MissingDataException;
import convex.core.exceptions.ResultException;
import convex.core.store.MemoryStore;

public class RequestFailuresTest {

	/** A logger that records the level and cause of each call. */
	@SuppressWarnings("serial")
	public static final class RecordingLogger extends LegacyAbstractLogger {
		public final List<Level> levels=new ArrayList<>();
		public final List<Throwable> causes=new ArrayList<>();

		@Override
		protected void handleNormalizedLoggingCall(Level level, Marker marker, String pattern, Object[] args, Throwable t) {
			levels.add(level);
			causes.add(t);
		}

		@Override protected String getFullyQualifiedCallerName() { return null; }
		@Override public boolean isTraceEnabled() { return true; }
		@Override public boolean isDebugEnabled() { return true; }
		@Override public boolean isInfoEnabled() { return true; }
		@Override public boolean isWarnEnabled() { return true; }
		@Override public boolean isErrorEnabled() { return true; }
	}

	@Test
	public void aDefectIsWarnedOfOnce() {
		RecordingLogger logger=new RecordingLogger();
		RequestFailures failures=new RequestFailures(logger);
		AssertionError defect=new AssertionError("simulated");
		failures.log(defect,"Dropped {} request","QUERY");
		failures.log(new NoClassDefFoundError("simulated"),"Dropped {} request","QUERY");
		assertEquals(List.of(Level.WARN,Level.DEBUG),logger.levels);
		assertSame(defect,logger.causes.get(0));
	}

	@Test
	public void runningOutOfMemoryIsWarnedOfOnceApartFromDefects() {
		RecordingLogger logger=new RecordingLogger();
		RequestFailures failures=new RequestFailures(logger);
		OutOfMemoryError oom=new OutOfMemoryError("simulated");
		failures.log(oom,"Dropped {} request","QUERY");
		failures.log(new OutOfMemoryError("simulated"),"Dropped {} request","QUERY");
		failures.log(new AssertionError("simulated"),"Dropped {} request","QUERY");
		assertEquals(List.of(Level.WARN,Level.DEBUG,Level.WARN),logger.levels);
		assertSame(oom,logger.causes.get(0));
	}

	@Test
	public void otherFailuresAreLoggedAtDebug() {
		RecordingLogger logger=new RecordingLogger();
		RequestFailures failures=new RequestFailures(logger);
		List<Throwable> others=List.of(new StackOverflowError(),new IllegalStateException(),
			new BadFormatException("simulated"));
		for (Throwable e: others) failures.log(e,"Dropped {} request","QUERY");
		assertEquals(List.of(Level.DEBUG,Level.DEBUG,Level.DEBUG),logger.levels);
		assertEquals(others,logger.causes);
	}

	@Test
	public void aTypedFailureKeepsItsMeaning() {
		assertEquals(ErrorCodes.FORMAT,RequestFailures.reply(new BadFormatException("simulated")).getErrorCode());
		assertEquals(ErrorCodes.FORMAT,RequestFailures.reply(new InvalidDataException("simulated",null)).getErrorCode());
		assertEquals(ErrorCodes.FORMAT,RequestFailures.reply(new MisbehaviourException("simulated")).getErrorCode());
		assertEquals(ErrorCodes.SIGNATURE,RequestFailures.reply(new BadSignatureException("simulated",null)).getErrorCode());
		MissingDataException missing=new MissingDataException(new MemoryStore(),Hash.EMPTY_HASH);
		assertEquals(ErrorCodes.MISSING,RequestFailures.reply(missing).getErrorCode());
		Result own=Result.error(ErrorCodes.TRUST,"simulated");
		assertSame(own,RequestFailures.reply(new ResultException(own)));
	}

	@Test
	public void anyOtherFailureIsThePeers() {
		assertEquals(ErrorCodes.LOAD,RequestFailures.reply(new OutOfMemoryError()).getErrorCode());
		assertEquals(ErrorCodes.LIMIT,RequestFailures.reply(new StackOverflowError()).getErrorCode());
		for (Throwable e: List.of(new NoClassDefFoundError(),new AssertionError(),
				new IllegalStateException(),new NullPointerException(),new IOException())) {
			Result r=RequestFailures.reply(e);
			assertEquals(ErrorCodes.PEER,r.getErrorCode(),e.toString());
			// No stack trace of the peer's reaches the client
			assertNull(r.getInfo().get(Keywords.TRACE),e.toString());
		}
	}
}
