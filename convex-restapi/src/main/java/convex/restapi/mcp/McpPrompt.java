package convex.restapi.mcp;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.lang.RT;

/**
 * Base class representing an MCP prompt with metadata loaded from JSON resources.
 *
 * <p>Mirrors the {@link McpTool} pattern: metadata is loaded from a JSON resource
 * file, and subclasses implement {@link #render(AMap)} to produce prompt messages.
 *
 * <p>Per the MCP specification, prompts are reusable templates that guide AI
 * through multi-step workflows using available tools.
 */
public abstract class McpPrompt {

	private final AMap<AString, ACell> metadata;
	private final String name;

	protected McpPrompt(AMap<AString, ACell> metadata) {
		this.metadata = metadata;
		AString nameCell = RT.ensureString(metadata.get(Strings.create("name")));
		if (nameCell == null) {
			throw new IllegalArgumentException("Prompt metadata missing 'name'");
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
	 * Render the prompt with the given arguments, producing a list of messages.
	 *
	 * @param arguments User-supplied arguments (may be empty)
	 * @return Vector of message maps with "role" and "content" fields
	 */
	public abstract AVector<AMap<AString, ACell>> render(AMap<AString, ACell> arguments);

	/**
	 * Utility to load prompt metadata from a JSON resource.
	 * Reuses the same JSON loading mechanism as {@link McpTool#loadMetadata(String)}.
	 */
	public static AMap<AString, ACell> loadMetadata(String resourcePath) {
		return McpTool.loadMetadata(resourcePath);
	}

	/**
	 * Helper to build a single user message with text content.
	 */
	protected static AVector<AMap<AString, ACell>> userMessage(String text) {
		AMap<AString, ACell> content = Maps.of(
			"type", "text",
			"text", text
		);
		AMap<AString, ACell> message = Maps.of(
			"role", "user",
			"content", content
		);
		return Vectors.of(message);
	}
}
