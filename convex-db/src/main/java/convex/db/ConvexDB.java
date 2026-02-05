package convex.db;

/**
 * Entry point for Convex DB - SQL database layer for Convex lattice data.
 *
 * <p>Convex DB provides SQL query capabilities over lattice data structures
 * using Apache Calcite. It enables standard SQL access to KV databases,
 * distributed filesystems, and other lattice-backed stores.
 *
 * @see <a href="https://docs.convex.world/cad/037_kv_database">CAD037: KV Database</a>
 */
public class ConvexDB {

	/**
	 * Version string for the Convex DB module.
	 */
	public static final String VERSION = "0.8.3-SNAPSHOT";

	private ConvexDB() {
		// Static utility class
	}
}
