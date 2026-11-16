package convex.peer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

/**
 * Tests that a scope keeps the records it matches, and only those.
 */
public class ExpectedLogsTest {
	private static final Logger log=LoggerFactory.getLogger(ExpectedLogsTest.class);

	@Test
	public void keepsRecordsCarryingItsObjects() {
		IllegalStateException failure=new IllegalStateException("test");
		Object subject=new Object();
		ExpectedLogs logs=ExpectedLogs.carrying(failure,subject).open();
		try (logs) {
			log.warn("Thrown",failure);
			log.error("Cause",new RuntimeException("wrapper",failure));
			log.warn("Argument {}",subject);
		}
		List<ExpectedLogs.Entry> kept=logs.records();
		assertEquals(3,kept.size());
		assertSame(failure,kept.get(0).thrown());
		assertEquals(Level.ERROR,kept.get(1).level());
		logs.assertLogged(Level.WARN,failure);
		logs.assertLogged(Level.ERROR,failure);
	}

	@Test
	public void keepsRecordsOfItsLoggerLoggedOnItsThreads() {
		Logger other=LoggerFactory.getLogger(ExpectedLogsTest.class.getName()+".other");
		ExpectedLogs logs=ExpectedLogs.onThreads(log.getName(),Thread.currentThread()).open();
		// Keeps the other logger's record out of the test output
		ExpectedLogs otherLogs=ExpectedLogs.onThreads(other.getName(),Thread.currentThread()).open();
		try (logs; otherLogs) {
			log.warn("This thread");
			other.warn("This thread, another logger");
		}
		assertEquals(List.of(log.getName()),logs.records().stream().map(ExpectedLogs.Entry::logger).toList());
	}

	@Test
	public void leavesOtherRecordsToBeWritten() throws Exception {
		IllegalStateException failure=new IllegalStateException("test");
		ExpectedLogs carrying=ExpectedLogs.carrying(failure).open();
		Thread other=new Thread(() -> log.warn("Unrelated",new IllegalStateException("other")));
		// Opened second, so it keeps only what the first leaves; keeps the unrelated record out of the test output
		ExpectedLogs elsewhere=ExpectedLogs.onThreads(log.getName(),other).open();
		try (carrying; elsewhere) {
			log.warn("Expected",failure);
			other.start();
			other.join();
		}
		assertEquals(1,carrying.records().size());
		assertEquals(1,elsewhere.records().size());
	}

	@Test
	public void keepsNothingBelowWarn() {
		IllegalStateException failure=new IllegalStateException("test");
		ExpectedLogs logs=ExpectedLogs.carrying(failure).open();
		log.info("Information",failure);
		assertThrows(AssertionError.class,logs::close,"a scope that kept nothing fails");
	}

	@Test
	public void failsIfNothingWasLogged() {
		ExpectedLogs logs=ExpectedLogs.carrying(new Object()).open();
		assertThrows(AssertionError.class,logs::close);
	}
}
