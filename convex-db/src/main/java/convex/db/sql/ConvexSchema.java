package convex.db.sql;

import java.util.HashMap;
import java.util.Map;

import org.apache.calcite.schema.Table;
import org.apache.calcite.schema.impl.AbstractSchema;

import convex.db.lattice.LatticeTables;

/**
 * Calcite Schema implementation backed by Convex lattice tables.
 *
 * <p>Exposes all tables in a LatticeTables instance to Calcite for SQL querying.
 */
public class ConvexSchema extends AbstractSchema {

	private final LatticeTables tables;

	public ConvexSchema(LatticeTables tables) {
		this.tables = tables;
	}

	@Override
	protected Map<String, Table> getTableMap() {
		Map<String, Table> tableMap = new HashMap<>();

		String[] tableNames = tables.getTableNames();
		for (String name : tableNames) {
			tableMap.put(name, new ConvexTable(tables, name));
		}

		return tableMap;
	}

	/**
	 * Gets the underlying LatticeTables instance.
	 *
	 * @return LatticeTables
	 */
	public LatticeTables getTables() {
		return tables;
	}
}
