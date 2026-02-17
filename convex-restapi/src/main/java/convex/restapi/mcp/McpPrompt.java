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
 * An MCP prompt loaded entirely from a JSON resource file.
 *
 * <p>JSON files contain both the metadata (name, title, description, arguments)
 * and the message templates. Templates use {@code ${variableName}} placeholders
 * that are substituted from the user-supplied arguments at render time.
 *
 * <p>See {@code docs/MCP_PROMPTS.md} for design principles and authoring guidelines.
 *
 * <p>Example JSON structure:
 * <pre>{@code
 * {
 *   "name": "explore-account",
 *   "title": "Explore Account",
 *   "description": "...",
 *   "arguments": [
 *     { "name": "address", "description": "...", "required": true }
 *   ],
 *   "messages": [
 *     { "role": "user", "content": "Explore account ${address}..." },
 *     { "role": "assistant", "content": "I'll explore ${address}..." }
 *   ]
 * }
 * }</pre>
 */
public class McpPrompt {

	private static final AString KEY_MESSAGES = Strings.create("messages");
	private static final AString KEY_ROLE = Strings.create("role");
	private static final AString KEY_CONTENT = Strings.create("content");
	private static final AString KEY_TYPE = Strings.create("type");
	private static final AString KEY_TEXT = Strings.create("text");
	private static final AString KEY_NAME = Strings.create("name");

	private final AMap<AString, ACell> metadata;
	private final String name;
	private final AVector<AMap<AString, ACell>> messageTemplates;

	public McpPrompt(AMap<AString, ACell> metadata) {
		this.metadata = metadata;
		AString nameCell = RT.ensureString(metadata.get(KEY_NAME));
		if (nameCell == null) {
			throw new IllegalArgumentException("Prompt metadata missing 'name'");
		}
		this.name = nameCell.toString();

		// Extract message templates from metadata
		AVector<AMap<AString, ACell>> msgs = RT.ensureVector(metadata.get(KEY_MESSAGES));
		if (msgs == null || msgs.isEmpty()) {
			throw new IllegalArgumentException("Prompt '" + name + "' missing 'messages' array");
		}
		this.messageTemplates = msgs;
	}

	public String getName() {
		return name;
	}

	/**
	 * Returns metadata for prompts/list, excluding the messages array
	 * (messages are only returned via prompts/get after rendering).
	 */
	public AMap<AString, ACell> getMetadata() {
		return metadata.dissoc(KEY_MESSAGES);
	}

	/**
	 * Render the prompt by substituting {@code ${argName}} placeholders
	 * in each message template with the supplied argument values.
	 *
	 * @param arguments User-supplied arguments (may be empty)
	 * @return Vector of message maps with "role" and "content" fields
	 */
	public AVector<AMap<AString, ACell>> render(AMap<AString, ACell> arguments) {
		if (arguments == null) arguments = Maps.empty();

		AVector<AMap<AString, ACell>> result = Vectors.empty();
		for (long i = 0; i < messageTemplates.count(); i++) {
			AMap<AString, ACell> template = messageTemplates.get(i);
			String role = RT.ensureString(template.get(KEY_ROLE)).toString();
			String contentTemplate = RT.ensureString(template.get(KEY_CONTENT)).toString();

			// Substitute ${argName} placeholders
			String text = substituteArgs(contentTemplate, arguments);

			AMap<AString, ACell> content = Maps.of(KEY_TYPE, KEY_TEXT, KEY_TEXT, Strings.create(text));
			AMap<AString, ACell> message = Maps.of(KEY_ROLE, Strings.create(role), KEY_CONTENT, content);
			result = result.conj(message);
		}
		return result;
	}

	/**
	 * Replace all {@code ${argName}} placeholders in the template with
	 * argument values. Unmatched placeholders are left as-is.
	 */
	private static String substituteArgs(String template, AMap<AString, ACell> arguments) {
		StringBuilder sb = new StringBuilder(template.length());
		int len = template.length();
		int i = 0;
		while (i < len) {
			if (i + 1 < len && template.charAt(i) == '$' && template.charAt(i + 1) == '{') {
				int end = template.indexOf('}', i + 2);
				if (end > 0) {
					String varName = template.substring(i + 2, end);
					ACell value = arguments.get(Strings.create(varName));
					if (value != null) {
						sb.append(RT.ensureString(value));
					} else {
						// Leave placeholder intact if no value supplied
						sb.append(template, i, end + 1);
					}
					i = end + 1;
					continue;
				}
			}
			sb.append(template.charAt(i));
			i++;
		}
		return sb.toString();
	}

	/**
	 * Load prompt metadata from a JSON resource file.
	 */
	public static AMap<AString, ACell> loadMetadata(String resourcePath) {
		return McpTool.loadMetadata(resourcePath);
	}
}
