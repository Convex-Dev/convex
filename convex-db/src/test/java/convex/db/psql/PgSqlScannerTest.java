package convex.db.psql;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

/** PostgreSQL's lexical rules for literals, identifiers and comments in statement splitting. */
public class PgSqlScannerTest {

	@Test
	public void testSplit() {
		assertEquals(List.of("SELECT 1", "SELECT 2"), PgSqlScanner.split("SELECT 1; SELECT 2;"));
		assertEquals(List.of("SELECT 1"), PgSqlScanner.split(" ; SELECT 1 ;; "));
		assertEquals(List.of(), PgSqlScanner.split(""));
	}

	@Test
	public void testSemicolonsInsideAreNotSeparators() {
		assertEquals(List.of("INSERT INTO t VALUES ('a; b')", "SELECT 1"),
			PgSqlScanner.split("INSERT INTO t VALUES ('a; b'); SELECT 1"));
		assertEquals(List.of("SELECT 'it''s; here'"), PgSqlScanner.split("SELECT 'it''s; here'"));
		assertEquals(List.of("SELECT E'a\\'; b'"), PgSqlScanner.split("SELECT E'a\\'; b'"));
		assertEquals(List.of("SELECT \"a;b\" FROM t"), PgSqlScanner.split("SELECT \"a;b\" FROM t"));
		assertEquals(List.of("SELECT $$a; b$$", "SELECT $x$ $$; $x$"), PgSqlScanner.split("SELECT $$a; b$$; SELECT $x$ $$; $x$"));
		assertEquals(List.of("SELECT 1 -- one; two", "SELECT 2"), PgSqlScanner.split("SELECT 1 -- one; two\n; SELECT 2"));
		assertEquals(List.of("SELECT /* a /* b; */ c; */ 1"), PgSqlScanner.split("SELECT /* a /* b; */ c; */ 1"));
	}

	@Test
	public void testParametersAndIdentifiersWithDollars() {
		assertEquals(List.of("SELECT * FROM t WHERE a = $1", "SELECT $2"), PgSqlScanner.split("SELECT * FROM t WHERE a = $1; SELECT $2"));
		assertEquals(List.of("SELECT a$b$ FROM t", "SELECT 1"), PgSqlScanner.split("SELECT a$b$ FROM t; SELECT 1"));
		// "e" ending an identifier does not make the next literal an escape string
		assertEquals(List.of("SELECT name'\\'", "x"), PgSqlScanner.split("SELECT name'\\'; x"));
	}

	@Test
	public void testUnterminatedRunsToTheEnd() {
		assertEquals(List.of("SELECT 'a; b"), PgSqlScanner.split("SELECT 'a; b"));
		assertEquals(List.of("SELECT 1 /* x; y"), PgSqlScanner.split("SELECT 1 /* x; y"));
	}

	@Test
	public void testCode() {
		assertEquals("SELECT '' FROM \"\" WHERE x ~ ''   ", PgSqlScanner.code("SELECT 'a~b' FROM \"c~d\" WHERE x ~ $$e$$ -- ~\n/* ~ */"));
	}
}
