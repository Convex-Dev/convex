package convex.restapi.mcp;

import java.io.IOException;
import java.io.InputStream;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Strings;
import convex.core.exceptions.ParseException;
import convex.core.json.JSON5Reader;
import convex.core.lang.RT;

/**
 * Base class representing an MCP tool with metadata loaded from JSON resources.
 */
public abstract class McpTool {

	private final AMap<AString, ACell> metadata;
	private final String name;

	protected McpTool(AMap<AString, ACell> metadata) {
		this.metadata = metadata;
		AString nameCell = RT.ensureString(metadata.get(Strings.create("name")));
		if (nameCell == null) {
			throw new IllegalArgumentException("Tool metadata missing 'name'");
		}
		this.name = nameCell.toString();
	}

	public String getName() {
		return name;
	}

	public AMap<AString, ACell> getMetadata() {
		return metadata;
	}

	/**
	 * Execute the tool using the supplied arguments.
	 */
	public abstract AMap<AString, ACell> handle(AMap<AString, ACell> arguments);

	/**
	 * Utility to load tool metadata from a JSON resource.
	 */
	public static AMap<AString, ACell> loadMetadata(String resourcePath) {
		try (InputStream is = McpTool.class.getClassLoader().getResourceAsStream(resourcePath)) {
			if (is == null) {
				throw new IllegalStateException("Tool metadata resource not found: " + resourcePath);
			}
			ACell cell = JSON5Reader.read(is);
			AMap<AString, ACell> metadata = RT.ensureMap(cell);

			if (metadata == null) {
				throw new IllegalStateException("Tool metadata must be a JSON object: " + resourcePath);
			}
			return metadata;
		} catch (IOException | ParseException e) {
			throw new IllegalStateException("Failed to read tool metadata: " + resourcePath, e);
		}
	}
}

