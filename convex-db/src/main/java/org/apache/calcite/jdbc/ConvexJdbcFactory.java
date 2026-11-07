package org.apache.calcite.jdbc;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.Properties;
import java.util.TimeZone;

import org.apache.calcite.adapter.java.JavaTypeFactory;
import org.apache.calcite.avatica.AvaticaConnection;
import org.apache.calcite.avatica.AvaticaSpecificDatabaseMetaData;
import org.apache.calcite.avatica.AvaticaFactory;
import org.apache.calcite.avatica.AvaticaPreparedStatement;
import org.apache.calcite.avatica.AvaticaStatement;
import org.apache.calcite.avatica.Meta;
import org.apache.calcite.avatica.NoSuchStatementException;
import org.apache.calcite.avatica.QueryState;
import org.apache.calcite.avatica.UnregisteredDriver;
import org.apache.calcite.config.CalciteConnectionConfig;
import org.apache.calcite.config.CalciteConnectionConfigImpl;
import org.apache.calcite.sql.validate.SqlConformance;
import org.apache.calcite.sql.validate.SqlDelegatingConformance;

import convex.db.jdbc.SQLExceptions;

/**
 * Keeps SQLSTATE on the SQLException exposed to JDBC callers, including lazy row
 * evaluation and errors in the static initialisers of Calcite's generated classes.
 * Like ConvexMeta, this lives here to access Calcite's package-private constructors.
 */
public class ConvexJdbcFactory extends CalciteFactory {

	private final AvaticaFactory delegate = new CalciteJdbc41Factory();

	public ConvexJdbcFactory() { super(4, 1); }

	@Override
	public AvaticaConnection newConnection(UnregisteredDriver driver, AvaticaFactory factory,
			String url, Properties info, CalciteSchema schema, JavaTypeFactory typeFactory) {
		return new Connection((Driver) driver, factory, url, info, schema, typeFactory);
	}

	@Override
	public AvaticaStatement newStatement(AvaticaConnection connection, Meta.StatementHandle handle,
			int type, int concurrency, int holdability) throws SQLException {
		return delegate.newStatement(connection, handle, type, concurrency, holdability);
	}

	@Override
	public AvaticaPreparedStatement newPreparedStatement(AvaticaConnection connection, Meta.StatementHandle handle,
			Meta.Signature signature, int type, int concurrency, int holdability) throws SQLException {
		return delegate.newPreparedStatement(connection, handle, signature, type, concurrency, holdability);
	}

	@Override
	public AvaticaSpecificDatabaseMetaData newDatabaseMetaData(AvaticaConnection connection) {
		return delegate.newDatabaseMetaData(connection);
	}

	@Override
	public ResultSetMetaData newResultSetMetaData(AvaticaStatement statement, Meta.Signature signature) throws SQLException {
		return delegate.newResultSetMetaData(statement, signature);
	}

	@Override
	public CalciteResultSet newResultSet(AvaticaStatement statement, QueryState state,
			Meta.Signature signature, TimeZone timeZone, Meta.Frame firstFrame) throws SQLException {
		return new CalciteResultSet(statement, (CalcitePrepare.CalciteSignature<?>) signature,
			newResultSetMetaData(statement, signature), timeZone, firstFrame) {
			@Override
			public boolean next() throws SQLException {
				try {
					return super.next();
				} catch (SQLException | RuntimeException | ExceptionInInitializerError e) {
					throw SQLExceptions.translate(e);
				}
			}
		};
	}

	private static class Connection extends CalciteConnectionImpl {
		Connection(Driver driver, AvaticaFactory factory, String url, Properties info,
				CalciteSchema schema, JavaTypeFactory typeFactory) {
			super(driver, factory, url, info, schema, typeFactory);
		}

		@Override
		public CalciteConnectionConfig config() {
			return new CalciteConnectionConfigImpl(info) {
				@Override
				public SqlConformance conformance() {
					return new SqlDelegatingConformance(super.conformance()) {
						@Override
						public boolean checkedArithmetic() { return true; }
					};
				}
			};
		}

		@Override
		protected ResultSet executeQueryInternal(AvaticaStatement statement, Meta.Signature signature,
				Meta.Frame firstFrame, QueryState state, boolean isUpdate) throws SQLException {
			try {
				return super.executeQueryInternal(statement, signature, firstFrame, state, isUpdate);
			} catch (SQLException | RuntimeException | ExceptionInInitializerError e) {
				throw SQLExceptions.translate(e);
			}
		}

		@Override
		protected Meta.ExecuteResult prepareAndExecuteInternal(AvaticaStatement statement, String sql,
				long maxRowCount) throws SQLException, NoSuchStatementException {
			try {
				return super.prepareAndExecuteInternal(statement, sql, maxRowCount);
			} catch (SQLException | RuntimeException | ExceptionInInitializerError e) {
				throw SQLExceptions.translate(e);
			}
		}

		@Override
		public CalcitePreparedStatement prepareStatement(String sql, int type, int concurrency,
				int holdability) throws SQLException {
			try {
				return super.prepareStatement(sql, type, concurrency, holdability);
			} catch (SQLException | RuntimeException | ExceptionInInitializerError e) {
				throw SQLExceptions.translate(e);
			}
		}
	}
}
