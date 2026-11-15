package convex.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.slf4j.Marker;
import org.slf4j.event.Level;
import org.slf4j.helpers.LegacyAbstractLogger;

import convex.core.exceptions.BadFormatException;

public class RequestFailureLogTest {

	/** A logger that records the level and cause of each call. */
	@SuppressWarnings("serial")
	static final class RecordingLogger extends LegacyAbstractLogger {
		final List<Level> levels=new ArrayList<>();
		final List<Throwable> causes=new ArrayList<>();

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
		RequestFailureLog failures=new RequestFailureLog(logger);
		AssertionError defect=new AssertionError("simulated");
		failures.log(defect,"Dropped {} request","QUERY");
		failures.log(new NoClassDefFoundError("simulated"),"Dropped {} request","QUERY");
		assertEquals(List.of(Level.WARN,Level.DEBUG),logger.levels);
		assertSame(defect,logger.causes.get(0));
	}

	@Test
	public void runningOutOfMemoryIsWarnedOfOnceApartFromDefects() {
		RecordingLogger logger=new RecordingLogger();
		RequestFailureLog failures=new RequestFailureLog(logger);
		OutOfMemoryError oom=new OutOfMemoryError("simulated");
		failures.log(oom,"Dropped {} request","QUERY");
		failures.log(new OutOfMemoryError("simulated"),"Dropped {} request","QUERY");
		failures.log(new AssertionError("simulated"),"Dropped {} request","QUERY");
		assertEquals(List.of(Level.WARN,Level.DEBUG,Level.WARN),logger.levels);
		assertSame(oom,logger.causes.get(0));
	}

	@Test
	public void onlyExceptionsAndStackOverflowsMayBeCausedByTheRequest() {
		assertTrue(RequestFailureLog.mayBeCausedByRequest(new BadFormatException("simulated")));
		assertTrue(RequestFailureLog.mayBeCausedByRequest(new IllegalStateException()));
		assertTrue(RequestFailureLog.mayBeCausedByRequest(new StackOverflowError()));
		assertFalse(RequestFailureLog.mayBeCausedByRequest(new OutOfMemoryError()));
		assertFalse(RequestFailureLog.mayBeCausedByRequest(new NoClassDefFoundError()));
		assertFalse(RequestFailureLog.mayBeCausedByRequest(new AssertionError()));
	}

	@Test
	public void otherFailuresAreLoggedAtDebug() {
		RecordingLogger logger=new RecordingLogger();
		RequestFailureLog failures=new RequestFailureLog(logger);
		List<Throwable> others=List.of(new StackOverflowError(),new IllegalStateException(),
			new BadFormatException("simulated"));
		for (Throwable e: others) failures.log(e,"Dropped {} request","QUERY");
		assertEquals(List.of(Level.DEBUG,Level.DEBUG,Level.DEBUG),logger.levels);
		assertEquals(others,logger.causes);
	}
}
