package org.apache.calcite.jdbc;

import java.sql.SQLException;

import org.apache.calcite.avatica.AvaticaConnection;
import org.apache.calcite.schema.SchemaPlus;

import convex.db.calcite.ConvexSchema;
import convex.db.lattice.SQLDatabase;

/**
 * Calcite Meta implementation for Convex SQL databases.
 *
 * <p>Adds JDBC transaction support by overriding CalciteMetaImpl (which throws
 * UnsupportedOperationException for commit/rollback). Transaction isolation
 * uses Convex's lattice component fork/sync model:
 * <ul>
 *   <li><b>BEGIN</b> (setAutoCommit(false)): forks the SQLDatabase component</li>
 *   <li><b>COMMIT</b>: syncs a writable fork back into the live database;
 *       a read-only snapshot is discarded</li>
 *   <li><b>ROLLBACK</b>: discards the fork</li>
 * </ul>
 *
 * <p>During a transaction, the connection's ConvexSchema is swapped to one
 * backed by the fork's working cursor, so SELECT queries read from the fork. Other
 * connections continue to read from the live cursor.
 *
 * <p>An optional durability action, supplied by the driver for store-backed
 * databases, runs after each writable commit and when a writable connection closes.
 * A read-only connection requests it only to retain the save obligation of earlier writes.
 *
 * <p>Must live in {@code org.apache.calcite.jdbc} to access the package-private
 * CalciteConnectionImpl.
 */
public class ConvexMeta extends CalciteMetaImpl {

	private final CalciteConnectionImpl calciteConnection;

	/** Saves published state durably, or null for an in-memory database. */
	private final Runnable durability;
	private final boolean permanentlyReadOnly;
	private boolean readOnly;
	private boolean savePending;
	/** Retained only for a reversible JDBC setting, never for a restricted binding. */
	private ConvexSchema writableSchema;

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

	protected ConvexMeta(CalciteConnectionImpl connection, Runnable durability) {
		this(connection, durability, false);
	}

	protected ConvexMeta(CalciteConnectionImpl connection, Runnable durability, boolean readOnly) {
		super(connection,
			CalciteMetaTableFactoryImpl.INSTANCE,
			CalciteMetaColumnFactoryImpl.INSTANCE);
		this.calciteConnection = connection;
		this.durability = durability;
		this.permanentlyReadOnly = readOnly;
		this.readOnly = readOnly;
	}

	/** Creates a meta for an in-memory database. */
	public static ConvexMeta create(AvaticaConnection connection) {
		return create(connection, null);
	}

	/**
	 * Factory method called by {@link convex.db.jdbc.ConvexDriver#createMeta}.
	 *
	 * @param durability Saves published state durably, or null for none
	 */
	public static ConvexMeta create(AvaticaConnection connection, Runnable durability) {
		return create(connection, durability, false);
	}

	/** Creates transaction handling with an immutable upper bound on write access. */
	public static ConvexMeta create(AvaticaConnection connection, Runnable durability, boolean readOnly) {
		return new ConvexMeta((CalciteConnectionImpl) connection, durability, readOnly);
	}

	/** Syncs fork to parent and saves, then starts a new fork if still in manual-commit mode. */
	@Override
	public void commit(ConnectionHandle ch) {
		if (txDatabase != null) {
			if (!readOnly) txDatabase.sync();
			endTransaction();
		}
		save();
		if (!autoCommit) {
			beginTransaction();
		}
	}

	/** Saves published state, excluding any open transaction, before closing. */
	@Override
	public void closeConnection(ConnectionHandle ch) {
		try {
			save();
		} finally {
			super.closeConnection(ch);
		}
	}

	private void save() {
		if ((!readOnly || savePending) && durability != null) {
			durability.run();
			savePending = false;
		}
	}

	/** Retains the save obligation of earlier writes when the caller later becomes read-only. */
	public void writeAttempted() { if (durability != null) savePending = true; }

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
		Boolean requestedReadOnly = connProps.isReadOnly();
		if (Boolean.FALSE.equals(requestedReadOnly) && permanentlyReadOnly) {
			throw new RuntimeException(new SQLException("Database binding is permanently read-only", "25006"));
		}
		if (requestedReadOnly != null && requestedReadOnly != readOnly) {
			if (!autoCommit) {
				throw new RuntimeException(new SQLException("Cannot change read-only mode during a transaction", "25001"));
			}
			String name = getSchemaName();
			ConvexSchema schema = name == null ? null : findConvexSchema(name);
			if (schema != null) {
				if (requestedReadOnly) {
					writableSchema = schema;
					schema = new ConvexSchema(schema.getDatabase().readOnly(), name);
				} else if (writableSchema != null) {
					schema = writableSchema;
					writableSchema = null;
				}
				calciteConnection.getRootSchema().add(name, schema);
			}
			readOnly = requestedReadOnly;
		}
		// Missing properties are null, not requests to reset the connection defaults.
		Boolean requestedAutoCommit = connProps.isAutoCommit();
		if (requestedAutoCommit != null && requestedAutoCommit != autoCommit) {
			if (!requestedAutoCommit) {
				beginTransaction();
			} else if (txDatabase != null) {
				if (!readOnly) txDatabase.sync();
				endTransaction();
				save();
			}
			autoCommit = requestedAutoCommit;
		}
		return super.connectionSync(ch, connProps);
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
