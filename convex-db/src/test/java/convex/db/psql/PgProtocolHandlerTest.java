package convex.db.psql;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Tests for the PostgreSQL query rewriting in PgProtocolHandler.
 */
public class PgProtocolHandlerTest {

	@Test
	public void testStripCasts() {
		assertEquals("SELECT id FROM t", PgProtocolHandler.stripCasts("SELECT id::integer FROM t"));
		assertEquals("SELECT id FROM t", PgProtocolHandler.stripCasts("SELECT id::int FROM t"));
		assertEquals("SELECT id FROM t", PgProtocolHandler.stripCasts("SELECT id::bigint FROM t"));
		assertEquals("SELECT 'a', 1", PgProtocolHandler.stripCasts("SELECT 'a'::text, 1::oid"));

		// ::int must not consume the prefix of a longer type name
		assertEquals("SELECT id FROM t", PgProtocolHandler.stripCasts("SELECT id::int4 FROM t"));
		assertEquals("SELECT id FROM t", PgProtocolHandler.stripCasts("SELECT id::int8 FROM t"));
		assertEquals("SELECT x::interval", PgProtocolHandler.stripCasts("SELECT x::interval"));
		assertEquals("SELECT x::texture", PgProtocolHandler.stripCasts("SELECT x::texture"));

		// Type names are case-insensitive
		assertEquals("SELECT id FROM t", PgProtocolHandler.stripCasts("SELECT id::INT4 FROM t"));

		// Adjacent punctuation still terminates the type name
		assertEquals("SELECT (id), n", PgProtocolHandler.stripCasts("SELECT (id::int), n::varchar"));
	}

	@Test
	public void testHasRegexOperator() {
		assertTrue(PgProtocolHandler.hasRegexOperator("SELECT * FROM t WHERE name ~ '^A'"));
		assertTrue(PgProtocolHandler.hasRegexOperator("SELECT * FROM t WHERE name ~* '^a'"));
		assertTrue(PgProtocolHandler.hasRegexOperator("SELECT * FROM t WHERE name !~ '^A'"));
		assertTrue(PgProtocolHandler.hasRegexOperator("SELECT * FROM t WHERE name !~* '^a'"));

		assertFalse(PgProtocolHandler.hasRegexOperator("SELECT * FROM t WHERE id = 1"));
		assertFalse(PgProtocolHandler.hasRegexOperator("SELECT * FROM t WHERE name ~~ 'A%'"));

		// A '~' inside a literal or quoted identifier is not an operator
		assertFalse(PgProtocolHandler.hasRegexOperator("SELECT * FROM t WHERE path = '~/data'"));
		assertFalse(PgProtocolHandler.hasRegexOperator("SELECT * FROM t WHERE s = 'it''s ~* here'"));
		assertFalse(PgProtocolHandler.hasRegexOperator("SELECT \"a~b\" FROM t"));

		// ... but an operator alongside such a literal still counts
		assertTrue(PgProtocolHandler.hasRegexOperator("SELECT * FROM t WHERE path = '~/data' AND name ~ 'x'"));
	}
}
