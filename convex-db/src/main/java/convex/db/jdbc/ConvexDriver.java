package convex.db.jdbc;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.calcite.avatica.AvaticaConnection;
import org.apache.calcite.avatica.Meta;
import org.apache.calcite.jdbc.CalciteConnection;
import org.apache.calcite.jdbc.ConvexMeta;
import org.apache.calcite.jdbc.Driver;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.runtime.Hook;
import org.apache.calcite.schema.SchemaPlus;

import convex.core.store.AStore;
import convex.db.ConvexDB;
import convex.db.calcite.ConvexSchema;
import convex.db.calcite.ConvexSchemaFactory;
import convex.db.calcite.ConvexLogicalTableModifyRule;
import convex.db.calcite.ConvexTableModifyRule;
import convex.db.calcite.rules.ConvexRules;
import convex.db.lattice.SQLDatabase;
import convex.etch.EtchStore;
import convex.node.NodeServer;

import org.apache.calcite.adapter.enumerable.EnumerableRules;

/**
 * JDBC Driver for Convex SQL databases.
 *
 * <p>Provides standard JDBC connectivity to Convex lattice-backed tables
 * via Apache Calcite. This is a local, in-process driver — there is no
 * network protocol between the JDBC client and the database.
 *
 * <p>Connection URL formats:
 * <pre>
 * jdbc:convex:mydb                          — named in-memory database
 * jdbc:convex:mem:mydb                      — named in-memory (explicit)
 * jdbc:convex:file:/data/mydb.etch          — persistent (Etch-backed)
 * jdbc:convex:database=mydb                 — legacy format (uses registry)
 * </pre>
 *
 * <p>Parameters are appended with semicolons:
 * <pre>
 * jdbc:convex:file:/data/store.etch;database=market
 * </pre>
 *
 * <p>Usage:
 * <pre>
 * // Simple — no setup needed
 * Connection conn = DriverManager.getConnection("jdbc:convex:mydb");
 *
 * // Persistent
 * Connection conn = DriverManager.getConnection("jdbc:convex:file:/data/mydb.etch");
 *
 * // Direct from ConvexDB instance (no DriverManager)
 * ConvexDB cdb = ConvexDB.connect(server.getCursor());
 * Connection conn = cdb.getConnection("mydb");
 * </pre>
 *
 * <p><b>Note:</b> This driver registers Convex-specific planner rules globally via
 * a {@link Hook#PLANNER} hook. The query rules (SELECT) only match ConvexTable
 * and won't affect other Calcite adapters. However, the DML rule replacement
 * (removing ENUMERABLE_TABLE_MODIFICATION_RULE) is global.
 */
public class ConvexDriver extends Driver {

	public static final String PREFIX = "jdbc:convex:";

	/** Open database instances, keyed by canonical identifier. */
	public static final ConcurrentHashMap<String, ManagedInstance> instances = new ConcurrentHashMap<>();

	static {
		new ConvexDriver().register();

		// Register Convex rules with the planner.
		Hook.PLANNER.add((RelOptPlanner planner) -> {
			planner.removeRule(EnumerableRules.ENUMERABLE_TABLE_MODIFICATION_RULE);
			planner.addRule(ConvexTableModifyRule.INSTANCE);
			planner.addRule(ConvexLogicalTableModifyRule.INSTANCE);
			for (RelOptRule rule : ConvexRules.queryRules()) {
				planner.addRule(rule);
			}
		});
	}

	public ConvexDriver() {
		super();
	}

	@Override
	public Meta createMeta(AvaticaConnection connection) {
		return ConvexMeta.create(connection);
	}

	@Override
	protected String getConnectStringPrefix() {
		return PREFIX;
	}

	@Override
	public Connection connect(String url, Properties info) throws SQLException {
		if (!acceptsURL(url)) return null;

		// Parse the URL into mode + identifier + params
		ParsedURL parsed = parseURL(url, info);

		// Set Calcite defaults
		if (!info.containsKey("caseSensitive")) {
			info.setProperty("caseSensitive", "false");
		}
		if (!info.containsKey("parserFactory")) {
			info.setProperty("parserFactory", "convex.db.calcite.ConvexDdlExecutor#PARSER_FACTORY");
		}

		// Resolve the ConvexDB instance
		ConvexDB cdb = resolveInstance(parsed);
		if (cdb == null) {
			throw new SQLException("Cannot resolve database for URL: " + url);
		}

		// Get connection from Calcite
		Connection conn = super.connect(url, info);
		if (conn == null) return null;

		// Set up ConvexSchema
		if (parsed.database != null && conn instanceof CalciteConnection calciteConn) {
			SQLDatabase db = cdb.database(parsed.database);
			ConvexSchema schema = new ConvexSchema(db, parsed.database);
			SchemaPlus rootSchema = calciteConn.getRootSchema();
			rootSchema.add(parsed.database, schema);
			calciteConn.setSchema(parsed.database);
		}

		return conn;
	}

	/**
	 * Creates a JDBC Connection directly from a ConvexDB instance.
	 * Registers the instance in the managed map so DML generated code
	 * can find it.
	 *
	 * @param cdb ConvexDB instance
	 * @param database Database name within the ConvexDB
	 * @return JDBC Connection
	 */
	public static Connection connect(ConvexDB cdb, String database) throws SQLException {
		// Register the provided ConvexDB under the mem: key so that all code
		// paths (schema setup, DML generated code) find the same instance
		String key = "mem:" + database;
		instances.put(key, new ManagedInstance(cdb, null, null));

		Properties info = new Properties();
		info.setProperty("caseSensitive", "false");
		info.setProperty("parserFactory", "convex.db.calcite.ConvexDdlExecutor#PARSER_FACTORY");

		ConvexDriver driver = new ConvexDriver();
		// Use mem: URL — resolveInstance will find our pre-registered instance
		Connection conn = ((Driver) driver).connect(PREFIX + database, info);
		if (conn == null) {
			throw new SQLException("Failed to create connection");
		}

		return conn;
	}

	// ========== URL Parsing ==========

	/** Parsed URL components. */
	static class ParsedURL {
		enum Mode { MEM, FILE, LEGACY }
		final Mode mode;
		final String identifier; // name for mem, path for file, name for legacy
		final String database;   // database name within the ConvexDB

		ParsedURL(Mode mode, String identifier, String database) {
			this.mode = mode;
			this.identifier = identifier;
			this.database = database;
		}
	}

	/**
	 * Parses a JDBC URL into mode, identifier, and database name.
	 */
	ParsedURL parseURL(String url, Properties info) {
		String params = url.substring(PREFIX.length());

		// Split off semicolon-separated parameters
		String path = params;
		String dbParam = info.getProperty("database");
		if (params.contains(";")) {
			String[] parts = params.split(";", 2);
			path = parts[0];
			// Parse remaining params
			for (String kv : parts[1].split(";")) {
				int eq = kv.indexOf('=');
				if (eq > 0) {
					String key = kv.substring(0, eq).trim();
					String val = kv.substring(eq + 1).trim();
					if ("database".equals(key)) dbParam = val;
					info.setProperty(key, val);
				}
			}
		}

		// Determine mode
		if (path.startsWith("file:")) {
			String filePath = path.substring(5);
			String database = (dbParam != null) ? dbParam : stemName(filePath);
			return new ParsedURL(ParsedURL.Mode.FILE, filePath, database);
		}

		if (path.startsWith("mem:")) {
			String name = path.substring(4);
			String database = (dbParam != null) ? dbParam : name;
			return new ParsedURL(ParsedURL.Mode.MEM, name, database);
		}

		// Legacy: database=name
		if (path.startsWith("database=")) {
			String name = path.substring(9);
			return new ParsedURL(ParsedURL.Mode.LEGACY, name, name);
		}

		// Bare name — treat as mem:
		if (!path.isEmpty() && !path.contains("=")) {
			String database = (dbParam != null) ? dbParam : path;
			return new ParsedURL(ParsedURL.Mode.MEM, path, database);
		}

		// Fallback: check properties
		if (dbParam != null) {
			return new ParsedURL(ParsedURL.Mode.LEGACY, dbParam, dbParam);
		}

		return new ParsedURL(ParsedURL.Mode.MEM, "default", "default");
	}

	/** Extracts a name from a file path (stem without extension). */
	static String stemName(String path) {
		String name = new File(path).getName();
		int dot = name.lastIndexOf('.');
		return (dot > 0) ? name.substring(0, dot) : name;
	}

	// ========== Instance Resolution ==========

	/**
	 * Resolves or creates the ConvexDB instance for a parsed URL.
	 */
	ConvexDB resolveInstance(ParsedURL parsed) throws SQLException {
		switch (parsed.mode) {
			case LEGACY:
				// Use the existing static registry for backward compat
				ConvexDB legacy = ConvexDB.lookup(parsed.identifier);
				if (legacy == null) {
					// Fall through to mem: behaviour — auto-create
					return resolveMemInstance(parsed.identifier);
				}
				return legacy;

			case MEM:
				return resolveMemInstance(parsed.identifier);

			case FILE:
				return resolveFileInstance(parsed.identifier);

			default:
				throw new SQLException("Unknown mode: " + parsed.mode);
		}
	}

	/** Gets or creates a named in-memory instance. */
	private ConvexDB resolveMemInstance(String name) {
		String key = "mem:" + name;
		return instances.computeIfAbsent(key, k -> {
			ConvexDB cdb = ConvexDB.create();
			return new ManagedInstance(cdb, null, null);
		}).cdb;
	}

	/** Gets or creates a file-backed instance. */
	private ConvexDB resolveFileInstance(String path) throws SQLException {
		String key;
		try {
			key = "file:" + new File(path).getCanonicalPath();
		} catch (IOException e) {
			key = "file:" + new File(path).getAbsolutePath();
		}

		final String canonicalKey = key;
		ManagedInstance inst = instances.computeIfAbsent(canonicalKey, k -> {
			try {
				File file = new File(path);
				EtchStore store = EtchStore.create(file);
				NodeServer<?> server = ConvexDB.createNodeServer(store);
				server.launch();
				ConvexDB cdb = ConvexDB.connect(server.getCursor());
				return new ManagedInstance(cdb, server, store);
			} catch (Exception e) {
				throw new RuntimeException("Failed to open Etch store: " + path, e);
			}
		});
		return inst.cdb;
	}

	/**
	 * Closes and removes all managed instances. Intended for testing.
	 */
	public static void closeAll() {
		for (var entry : instances.entrySet()) {
			ManagedInstance inst = entry.getValue();
			try {
				if (inst.server != null) inst.server.close();
				if (inst.store != null) inst.store.close();
			} catch (Exception e) {
				// best effort
			}
		}
		instances.clear();
	}

	/** A managed ConvexDB instance with optional server and store. */
	public static class ManagedInstance {
		public final ConvexDB cdb;
		final NodeServer<?> server; // null for mem: mode
		final AStore store;         // null for mem: mode

		ManagedInstance(ConvexDB cdb, NodeServer<?> server, AStore store) {
			this.cdb = cdb;
			this.server = server;
			this.store = store;
		}
	}
}
