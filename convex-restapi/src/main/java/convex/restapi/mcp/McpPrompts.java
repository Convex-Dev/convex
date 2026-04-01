package convex.restapi.mcp;

/**
 * Registry of MCP prompts for guided workflows.
 *
 * <p>All prompt content (messages, templates, doc references) lives in JSON
 * resource files under {@code convex/restapi/mcp/prompts/}. This class
 * simply loads and registers them.
 *
 * <p>Three prompts are always available; three additional prompts are
 * registered only when the signing service is configured.
 *
 * <p>See {@code docs/MCP_PROMPTS.md} for design principles and authoring guidelines.
 *
 * @see McpPrompt
 */
class McpPrompts {

	private static final String PROMPTS_PATH = "convex/restapi/mcp/prompts/";

	private final McpAPI api;
	private final McpServer mcpServer;

	McpPrompts(McpAPI api, McpServer mcpServer) {
		this.api = api;
		this.mcpServer = mcpServer;
	}

	/**
	 * Register all prompts. Signing-dependent prompts are only registered
	 * when the signing service is available.
	 */
	void registerAll() {
		// Always available
		register("explore-account.json");
		register("network-status.json");
		register("convex-guide.json");

		// Only if signing service is available
		if (api.getRESTServer().getSigningService() != null) {
			register("create-account.json");
			register("deploy-contract.json");
			register("transfer-funds.json");
		}
	}

	private void register(String jsonFile) {
		mcpServer.registerPrompt(new McpPrompt(McpPrompt.loadMetadata(PROMPTS_PATH + jsonFile)));
	}
}
