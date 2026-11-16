package convex.core.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

public class CleanupTest {

	@Test
	public void noFailureFinishesQuietly() throws IOException {
		Cleanup cleanup=new Cleanup();
		List<Integer> ran=new ArrayList<>();
		cleanup.run(()->ran.add(1));
		cleanup.run(()->ran.add(2));
		cleanup.finish();
		cleanup.finish(IOException.class);
		assertEquals(List.of(1,2),ran);
	}

	@Test
	public void everyStepRunsAndTheFirstFailureCarriesTheRest() {
		IllegalStateException first=new IllegalStateException("first");
		IllegalArgumentException second=new IllegalArgumentException("second");
		Cleanup cleanup=new Cleanup();
		List<Integer> ran=new ArrayList<>();
		cleanup.run(()->{ ran.add(1); throw first; });
		cleanup.run(()->{ ran.add(2); throw second; });
		cleanup.run(()->ran.add(3));
		assertEquals(List.of(1,2,3),ran);

		IllegalStateException thrown=assertThrows(IllegalStateException.class,cleanup::finish);
		assertSame(first,thrown);
		assertArrayEquals(new Throwable[] {second},thrown.getSuppressed());
	}

	@Test
	public void aJvmErrorDoesNotStopLaterSteps() {
		OutOfMemoryError oom=new OutOfMemoryError("test");
		Cleanup cleanup=new Cleanup();
		List<Integer> ran=new ArrayList<>();
		cleanup.run(()->{ throw oom; });
		cleanup.run(()->ran.add(2));
		assertEquals(List.of(2),ran);
		assertSame(oom,assertThrows(OutOfMemoryError.class,cleanup::finish));
	}

	@Test
	public void anErrorOutranksExceptionsBeforeAndAfterIt() {
		IOException before=new IOException("before");
		InternalError error=new InternalError("error");
		IllegalStateException after=new IllegalStateException("after");
		Cleanup cleanup=new Cleanup();
		cleanup.run(()->{ throw before; });
		cleanup.run(()->{ throw error; });
		cleanup.run(()->{ throw after; });

		InternalError thrown=assertThrows(InternalError.class,()->cleanup.finish(IOException.class));
		assertSame(error,thrown);
		assertArrayEquals(new Throwable[] {before,after},thrown.getSuppressed());
	}

	@Test
	public void theFirstErrorOutranksALaterOne() {
		OutOfMemoryError first=new OutOfMemoryError("first");
		InternalError second=new InternalError("second");
		Cleanup cleanup=new Cleanup();
		cleanup.run(()->{ throw first; });
		cleanup.run(()->{ throw second; });

		assertSame(first,assertThrows(OutOfMemoryError.class,cleanup::finish));
		assertArrayEquals(new Throwable[] {second},first.getSuppressed());
	}

	@Test
	public void theSameFailureTwiceIsReportedOnce() {
		IllegalStateException failure=new IllegalStateException("shared");
		Cleanup cleanup=new Cleanup();
		cleanup.run(()->{ throw failure; });
		cleanup.run(()->{ throw failure; });
		IllegalStateException thrown=assertThrows(IllegalStateException.class,cleanup::finish);
		assertSame(failure,thrown);
		assertEquals(0,thrown.getSuppressed().length);
	}

	@Test
	public void anInterruptedStepRestoresTheInterrupt() {
		Cleanup cleanup=new Cleanup();
		try {
			cleanup.run(()->{ throw new InterruptedException("test"); });
			assertTrue(Thread.currentThread().isInterrupted());
		} finally {
			Thread.interrupted();
		}
		RuntimeException thrown=assertThrows(RuntimeException.class,cleanup::finish);
		assertTrue(thrown.getCause() instanceof InterruptedException);
	}

	@Test
	public void aFailureOfTheDeclaredTypeIsThrownAsItIs() {
		IOException failure=new IOException("test");
		Cleanup cleanup=new Cleanup();
		cleanup.run(()->{ throw failure; });
		assertSame(failure,assertThrows(IOException.class,()->cleanup.finish(IOException.class)));
	}

	@Test
	public void anUndeclaredCheckedFailureIsWrapped() {
		IOException io=new IOException("io");
		Cleanup ioCleanup=new Cleanup();
		ioCleanup.run(()->{ throw io; });
		assertSame(io,assertThrows(UncheckedIOException.class,ioCleanup::finish).getCause());

		Exception other=new Exception("other");
		Cleanup otherCleanup=new Cleanup();
		otherCleanup.run(()->{ throw other; });
		RuntimeException thrown=assertThrows(RuntimeException.class,
			()->otherCleanup.finish(IOException.class));
		assertSame(other,thrown.getCause());
	}
}
