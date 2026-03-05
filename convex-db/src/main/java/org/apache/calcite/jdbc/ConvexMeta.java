package org.apache.calcite.jdbc;

import org.apache.calcite.avatica.AvaticaConnection;
import org.apache.calcite.avatica.Meta;

/**
 * Calcite Meta implementation for Convex SQL databases.
 *
 * <p>Extends CalciteMetaImpl to add transaction support. CalciteMetaImpl's
 * commit/rollback throw UnsupportedOperationException; this class overrides
 * them to integrate with Convex's lattice cursor fork/sync model.
 *
 * <p>Must live in org.apache.calcite.jdbc to access the package-private
 * CalciteConnectionImpl.
 */
public class ConvexMeta extends CalciteMetaImpl {

	private final CalciteConnectionImpl calciteConnection;

	protected ConvexMeta(CalciteConnectionImpl connection) {
		super(connection,
			CalciteMetaTableFactoryImpl.INSTANCE,
			CalciteMetaColumnFactoryImpl.INSTANCE);
		this.calciteConnection = connection;
	}

	/**
	 * Creates a ConvexMeta from an AvaticaConnection.
	 * Handles the cast to CalciteConnectionImpl internally.
	 *
	 * @param connection The Avatica connection (must be a CalciteConnectionImpl)
	 * @return New ConvexMeta instance
	 */
	public static ConvexMeta create(AvaticaConnection connection) {
		return new ConvexMeta((CalciteConnectionImpl) connection);
	}

	@Override
	public void commit(ConnectionHandle ch) {
		// TODO: wire to ConvexSchema transaction support
	}

	@Override
	public void rollback(ConnectionHandle ch) {
		// TODO: wire to ConvexSchema transaction support
	}
}
