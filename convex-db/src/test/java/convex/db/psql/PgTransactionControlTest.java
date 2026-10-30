package convex.db.psql;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.SQLException;

import org.junit.jupiter.api.Test;

import convex.db.psql.PgTransactionControl.Kind;

/** Classification of transaction-control statements by their leading keywords. */
public class PgTransactionControlTest {

	@Test
	public void testBegin() throws SQLException {
		for (String sql : new String[] {"BEGIN", "begin;", "  Begin Work ", "BEGIN TRANSACTION",
				"START TRANSACTION", "BEGIN ISOLATION LEVEL REPEATABLE READ", "BEGIN ISOLATION LEVEL READ COMMITTED",
				"START TRANSACTION READ WRITE, NOT DEFERRABLE"}) {
			assertEquals(Kind.BEGIN, PgTransactionControl.classify(sql), sql);
		}
	}

	@Test
	public void testCommitAndRollback() throws SQLException {
		for (String sql : new String[] {"COMMIT", "commit work", "END", "END TRANSACTION", "COMMIT AND NO CHAIN"}) {
			assertEquals(Kind.COMMIT, PgTransactionControl.classify(sql), sql);
		}
		for (String sql : new String[] {"ROLLBACK", "rollback transaction;", "ABORT", "ABORT WORK"}) {
			assertEquals(Kind.ROLLBACK, PgTransactionControl.classify(sql), sql);
		}
	}

	@Test
	public void testOtherStatements() throws SQLException {
		for (String sql : new String[] {"SELECT 1", "", "  ", "START", "BEGINNING", "COMMITTED",
				"PREPARE stmt AS SELECT 1", "ENDING", "INSERT INTO t VALUES (1)"}) {
			assertNull(PgTransactionControl.classify(sql), sql);
		}
	}

	@Test
	public void testUnsupported() {
		for (String sql : new String[] {"SAVEPOINT a", "RELEASE SAVEPOINT a", "RELEASE a", "ROLLBACK TO a",
				"ROLLBACK WORK TO SAVEPOINT a", "COMMIT AND CHAIN", "BEGIN ISOLATION LEVEL SERIALIZABLE", "ROLLBACK AND CHAIN", "BEGIN READ ONLY",
				"START TRANSACTION ISOLATION LEVEL SERIALIZABLE, READ ONLY", "PREPARE TRANSACTION 'x'",
				"COMMIT PREPARED 'x'", "ROLLBACK PREPARED 'x'"}) {
			assertEquals("0A000", assertThrows(SQLException.class, () -> PgTransactionControl.classify(sql), sql).getSQLState(), sql);
		}
	}
}
