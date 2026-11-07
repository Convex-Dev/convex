package convex.db.jdbc;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.SQLException;

import org.junit.jupiter.api.Test;

class SQLExceptionsTest {

	@Test
	void preservesExplicitStateAndCause() {
		SQLException original = new SQLException("range", "22003", 17, new NumberFormatException("value"));
		assertSame(original, SQLExceptions.translate(original));
		SQLException wrapper = new SQLException("execution failed", new RuntimeException(original));
		SQLException mapped = SQLExceptions.translate(wrapper);
		assertEquals("22003", mapped.getSQLState());
		assertEquals(17, mapped.getErrorCode());
		assertSame(wrapper, mapped.getCause());
	}

	@Test
	void mapsGeneratedCodeInitialiserFailures() {
		for (String message : new String[] {"/ by zero", "BigInteger divide by zero", "Division by zero"}) {
			Error initialiser = new ExceptionInInitializerError(new ArithmeticException(message));
			SQLException mapped = SQLExceptions.translate(initialiser);
			assertEquals("22012", mapped.getSQLState());
			assertSame(initialiser, mapped.getCause());
		}
		assertEquals("22P02", SQLExceptions.translate(new ExceptionInInitializerError(new NumberFormatException())).getSQLState());
		assertEquals("22003", SQLExceptions.translate(new ArithmeticException("long overflow")).getSQLState());
	}

	@Test
	void doesNotClassifyUnknownFailuresByMessage() {
		RuntimeException bug = new IllegalStateException("division by zero, invalid number, overflow");
		assertSame(bug, assertThrows(IllegalStateException.class, () -> SQLExceptions.translate(bug)));
		Error initialiser = new ExceptionInInitializerError(bug);
		assertSame(initialiser, assertThrows(ExceptionInInitializerError.class, () -> SQLExceptions.translate(initialiser)));
		SQLException unknown = new SQLException("syntax error");
		assertSame(unknown, SQLExceptions.translate(unknown));
		Error fatal = new OutOfMemoryError("test only");
		assertSame(fatal, assertThrows(OutOfMemoryError.class, () -> SQLExceptions.translate(fatal)));
	}

	@Test
	void cyclicCauseChainTerminates() {
		RuntimeException first = new RuntimeException("first");
		RuntimeException second = new RuntimeException("second", first);
		first.initCause(second);
		assertSame(first, assertThrows(RuntimeException.class, () -> SQLExceptions.translate(first)));
	}
}
