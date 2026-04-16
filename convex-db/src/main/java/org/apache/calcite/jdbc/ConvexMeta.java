package org.apache.calcite.jdbc;

import java.util.Collections;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.calcite.avatica.AvaticaConnection;
import org.apache.calcite.avatica.Meta.ExecuteResult;
import org.apache.calcite.avatica.Meta.MetaResultSet;
import org.apache.calcite.avatica.Meta.PrepareCallback;
import org.apache.calcite.avatica.Meta.StatementHandle;
import org.apache.calcite.avatica.NoSuchStatementException;
import org.apache.calcite.schema.SchemaPlus;

import convex.db.calcite.ConvexSchema;
import convex.db.lattice.SQLDatabase;

/**
 * Calcite Meta implementation for Convex SQL databases.
 *
 * <p>Adds JDBC transaction support by overriding CalciteMetaImpl (which throws
 * UnsupportedOperationException for commit/rollback). Transaction isolation
 * uses Convex's lattice cursor fork/sync model:
 * <ul>
 *   <li><b>BEGIN</b> (setAutoCommit(false)): forks the SQLDatabase cursor</li>
 *   <li><b>COMMIT</b>: syncs the fork back to the parent</li>
 *   <li><b>ROLLBACK</b>: discards the fork</li>
 * </ul>
 *
 * <p>During a transaction, the connection's ConvexSchema is swapped to one
 * backed by the forked cursor, so SELECT queries read from the fork. Other
 * connections continue to read from the original cursor.
 *
 * <p>Must live in {@code org.apache.calcite.jdbc} to access the package-private
 * CalciteConnectionImpl.
 */
public class ConvexMeta extends CalciteMetaImpl {

	private final CalciteConnectionImpl calciteConnection;

	/** The forked database for the current transaction, or null if not in a transaction. */
	private SQLDatabase txDatabase;

	/** The original ConvexSchema to restore on commit/rollback. */
	private ConvexSchema originalSchema;

	/** JDBC-level autoCommit state. Initialised to true (JDBC default).
	 *  Transitions detected from connProps (not result) since Calcite's
	 *  internal autoCommit default differs from the JDBC standard. */
	private boolean autoCommit = true;

	/** Reentrancy guard: getSchema() triggers connectionSync() internally. */
	private boolean inSync = false;

	protected ConvexMeta(CalciteConnectionImpl connection) {
		super(connection,
			CalciteMetaTableFactoryImpl.INSTANCE,
			CalciteMetaColumnFactoryImpl.INSTANCE);
		this.calciteConnection = connection;
	}

	/** Factory method called by {@link convex.db.jdbc.ConvexDriver#createMeta}. */
	public static ConvexMeta create(AvaticaConnection connection) {
		return new ConvexMeta((CalciteConnectionImpl) connection);
	}

	// ── Secondary index DDL interception ─────────────────────────────────────

	private static final Pattern CREATE_INDEX = Pattern.compile(
		"(?i)CREATE\\s+INDEX\\s+(IF\\s+NOT\\s+EXISTS\\s+)?(\\w+)\\s+ON\\s+(\\w+)\\s*\\(\\s*(\\w+)[^)]*\\)\\s*");

	private static final Pattern DROP_INDEX = Pattern.compile(
		"(?i)DROP\\s+INDEX\\s+(IF\\s+EXISTS\\s+)?(\\w+)(?:\\s+ON\\s+(\\w+))?\\s*");

	@Override
	public ExecuteResult prepareAndExecute(StatementHandle h, String sql,
			long maxRowCount, int maxRowsInFirstFrame, PrepareCallback callback)
			throws NoSuchStatementException {
		Matcher m = CREATE_INDEX.matcher(sql.trim());
		if (m.matches()) {
			boolean ifNotExists = m.group(1) != null;
			String indexName  = m.group(2);
			String tableName  = m.group(3);
			String columnName = m.group(4);
			ConvexSchema schema = findConvexSchema(getSchemaName());
			if (schema != null) {
				schema.createIndex(indexName, tableName, columnName, ifNotExists);
			}
			return new ExecuteResult(Collections.singletonList(
					MetaResultSet.count(h.connectionId, h.id, 0L)));
		}

		m = DROP_INDEX.matcher(sql.trim());
		if (m.matches()) {
			boolean ifExists = m.group(1) != null;
			String indexName = m.group(2);
			ConvexSchema schema = findConvexSchema(getSchemaName());
			if (schema != null) {
				schema.dropIndex(indexName, ifExists);
			}
			return new ExecuteResult(Collections.singletonList(
					MetaResultSet.count(h.connectionId, h.id, 0L)));
		}

		return super.prepareAndExecute(h, sql, maxRowCount, maxRowsInFirstFrame, callback);
	}

	/** Syncs fork to parent, then starts a new fork if still in manual-commit mode. */
	@Override
	public void commit(ConnectionHandle ch) {
		if (txDatabase != null) {
			txDatabase.sync();
			endTransaction();
		}
		if (!autoCommit) {
			beginTransaction();
		}
	}

	/** Discards fork, then starts a new fork if still in manual-commit mode. */
	@Override
	public void rollback(ConnectionHandle ch) {
		endTransaction();
		if (!autoCommit) {
			beginTransaction();
		}
	}

	@Override
	public ConnectionProperties connectionSync(ConnectionHandle ch, ConnectionProperties connProps) {
		if (inSync) return super.connectionSync(ch, connProps);
		inSync = true;
		try {
			return doConnectionSync(ch, connProps);
		} finally {
			inSync = false;
		}
	}

	/** Detects autoCommit transitions from the requested connProps. */
	private ConnectionProperties doConnectionSync(ConnectionHandle ch, ConnectionProperties connProps) {
		ConnectionProperties result = super.connectionSync(ch, connProps);

		try {
			// Read from connProps (the request), not result (Calcite internal state)
			boolean newAutoCommit = connProps.isAutoCommit();
			if (newAutoCommit != autoCommit) {
				if (!newAutoCommit) {
					beginTransaction();
					if (txDatabase != null) {
						autoCommit = false;
					}
				} else {
					if (txDatabase != null) {
						txDatabase.sync();
						endTransaction();
					}
					autoCommit = true;
				}
			}
		} catch (Exception e) {
			// connProps.isAutoCommit() throws if not explicitly set (e.g. during init)
		}

		return result;
	}

	/** Forks the SQLDatabase and swaps the connection's schema to the fork. */
	private void beginTransaction() {
		SQLDatabase db = findDatabase();
		if (db == null) return;

		String schemaName = getSchemaName();
		if (schemaName == null) return;

		ConvexSchema currentSchema = findConvexSchema(schemaName);
		if (currentSchema == null) return;

		originalSchema = currentSchema;
		txDatabase = db.fork();

		ConvexSchema txSchema = new ConvexSchema(txDatabase, schemaName);
		calciteConnection.getRootSchema().add(schemaName, txSchema);
	}

	/** Restores the original schema and discards the fork. */
	private void endTransaction() {
		if (txDatabase == null) return;

		String schemaName = getSchemaName();
		if (schemaName != null && originalSchema != null) {
			calciteConnection.getRootSchema().add(schemaName, originalSchema);
		}
		txDatabase = null;
		originalSchema = null;
	}

	private String getSchemaName() {
		try {
			return calciteConnection.getSchema();
		} catch (Exception e) {
			return null;
		}
	}

	private SQLDatabase findDatabase() {
		String schemaName = getSchemaName();
		if (schemaName == null) return null;
		ConvexSchema schema = findConvexSchema(schemaName);
		return (schema != null) ? schema.getDatabase() : null;
	}

	private ConvexSchema findConvexSchema(String schemaName) {
		try {
			SchemaPlus sub = calciteConnection.getRootSchema().getSubSchema(schemaName);
			if (sub != null) {
				return sub.unwrap(ConvexSchema.class);
			}
		} catch (Exception e) {
			// ignore
		}
		return null;
	}
}
