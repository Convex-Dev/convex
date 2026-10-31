package convex.db.calcite;

import static org.apache.calcite.util.Static.RESOURCE;
import static org.junit.jupiter.api.Assertions.*;

import org.apache.calcite.runtime.CalciteContextException;
import org.apache.calcite.sql.SqlUtil;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.junit.jupiter.api.Test;

/**
 * Validation errors are recognised from exceptions as Calcite formats and wraps them,
 * whatever names they carry.
 */
class ValidationErrorsTest {

	private static CalciteContextException raised(org.apache.calcite.runtime.Resources.ExInst<org.apache.calcite.sql.validate.SqlValidatorException> e) {
		return SqlUtil.newContextException(new SqlParserPos(1, 15, 1, 21), e);
	}

	@Test
	void undefinedTable() {
		assertEquals("42P01", ValidationErrors.sqlState(raised(RESOURCE.objectNotFound("MISSING"))));
		assertEquals("42P01", ValidationErrors.sqlState(raised(RESOURCE.objectNotFoundWithin("T", "db's 'schema'"))));
		assertEquals("42P01", ValidationErrors.sqlState(raised(RESOURCE.objectNotFoundWithinDidYouMean("USERS", "db", "users"))));
		assertEquals("42P01", ValidationErrors.sqlState(raised(RESOURCE.tableNameNotFound("X"))));
		assertEquals("42P01", ValidationErrors.sqlState(new RuntimeException("wrapped", raised(RESOURCE.objectNotFound("T")))));
	}

	@Test
	void otherErrors() {
		assertNull(ValidationErrors.sqlState(raised(RESOURCE.columnNotFound("C"))));
		assertNull(ValidationErrors.sqlState(new RuntimeException("Object 'T' not found")));
		assertNull(ValidationErrors.sqlState(null));
	}
}
