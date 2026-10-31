package convex.db.calcite;

import java.lang.reflect.Type;
import java.sql.SQLException;

import org.apache.calcite.prepare.CalcitePrepareImpl;
import org.apache.calcite.runtime.CalciteException;

/**
 * Calcite's statement preparation, through which every statement on a Convex DB
 * connection is parsed, validated and planned, DDL included. A validation error with
 * a known SQLSTATE ({@link ValidationErrors}) gains it in its cause chain, where
 * Convex DB places its codes for JDBC callers and the PostgreSQL wire server finds
 * them.
 */
public class ConvexPrepare extends CalcitePrepareImpl {

	@Override
	public <T> CalciteSignature<T> prepareSql(Context context, Query<T> query, Type elementType, long maxRowCount) {
		try {
			return super.prepareSql(context, query, elementType, maxRowCount);
		} catch (RuntimeException e) {
			String sqlState = ValidationErrors.sqlState(e);
			if (sqlState == null) throw e;
			throw new CalciteException(e.getMessage(), new SQLException(e.getMessage(), sqlState, e));
		}
	}
}
