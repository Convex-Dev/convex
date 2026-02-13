package convex.restapi.mcp;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Strings;
import convex.core.lang.RT;

/**
 * Registry of MCP prompts for guided workflows.
 *
 * <p>Prompts are reusable templates that guide AI agents through multi-step
 * tasks using the available MCP tools. Three prompts are always available;
 * three additional prompts are registered only when the signing service is
 * configured.
 *
 * @see McpPrompt
 */
class McpPrompts {

	private static final String PROMPTS_PATH = "convex/restapi/mcp/prompts/";

	private final McpAPI api;

	McpPrompts(McpAPI api) {
		this.api = api;
	}

	/**
	 * Register all prompts. Signing-dependent prompts are only registered
	 * when the signing service is available.
	 */
	void registerAll() {
		// Always available
		api.registerPrompt(new ExploreAccountPrompt());
		api.registerPrompt(new NetworkStatusPrompt());
		api.registerPrompt(new ConvexGuidePrompt());

		// Only if signing service is available
		if (api.getRESTServer().getSigningService() != null) {
			api.registerPrompt(new CreateAccountPrompt());
			api.registerPrompt(new DeployContractPrompt());
			api.registerPrompt(new TransferFundsPrompt());
		}
	}

	// ==================== Always Available ====================

	private class ExploreAccountPrompt extends McpPrompt {
		ExploreAccountPrompt() {
			super(McpPrompt.loadMetadata(PROMPTS_PATH + "explore-account.json"));
		}

		@Override
		public AVector<AMap<AString, ACell>> render(AMap<AString, ACell> arguments) {
			String address = getStringArg(arguments, "address", "#11");
			return userMessage(
				"Please explore Convex account " + address + ":\n\n" +
				"1. Use the `describeAccount` tool with address \"" + address + "\" to get the account's " +
				"balance, sequence number, public key, and defined symbols with metadata.\n\n" +
				"2. If the account has interesting definitions, use the `lookup` tool to examine " +
				"key symbols in detail.\n\n" +
				"3. Use the `query` tool with source \"(balance " + address + ")\" to get the current balance.\n\n" +
				"Summarise what this account is — is it a user account, an actor/smart contract, or a system account? " +
				"What functions does it expose?"
			);
		}
	}

	private class NetworkStatusPrompt extends McpPrompt {
		NetworkStatusPrompt() {
			super(McpPrompt.loadMetadata(PROMPTS_PATH + "network-status.json"));
		}

		@Override
		public AVector<AMap<AString, ACell>> render(AMap<AString, ACell> arguments) {
			return userMessage(
				"Please provide an overview of the Convex network:\n\n" +
				"1. Use the `peerStatus` tool to get current peer information including consensus state, " +
				"peer stake, and network connections.\n\n" +
				"2. Use the `query` tool with source \"(count (peers))\" to get the number of active peers.\n\n" +
				"3. Use the `query` tool with source \"(balance #9)\" to check the memory exchange balance " +
				"(a key system indicator).\n\n" +
				"Summarise the network health, consensus progress, and any notable state."
			);
		}
	}

	private class ConvexGuidePrompt extends McpPrompt {
		ConvexGuidePrompt() {
			super(McpPrompt.loadMetadata(PROMPTS_PATH + "convex-guide.json"));
		}

		@Override
		public AVector<AMap<AString, ACell>> render(AMap<AString, ACell> arguments) {
			String topic = getStringArg(arguments, "topic", "data types");
			return userMessage(
				"The user wants to learn about Convex Lisp topic: \"" + topic + "\"\n\n" +
				"Provide a clear, practical guide. Use the `query` tool to demonstrate examples " +
				"interactively. Cover:\n" +
				"- What the concept is and why it matters\n" +
				"- Key syntax and functions\n" +
				"- Practical examples the user can try\n\n" +
				"Common topics: data types (vectors, maps, sets, blobs), actors and smart contracts, " +
				"the asset system (@convex.asset, @convex.fungible), accounts and addresses, " +
				"the environment and definitions, error handling, loops and iteration."
			);
		}
	}

	// ==================== Signing Service Required ====================

	private class CreateAccountPrompt extends McpPrompt {
		CreateAccountPrompt() {
			super(McpPrompt.loadMetadata(PROMPTS_PATH + "create-account.json"));
		}

		@Override
		public AVector<AMap<AString, ACell>> render(AMap<AString, ACell> arguments) {
			String passphrase = getStringArg(arguments, "passphrase", null);
			String faucetAmount = getStringArg(arguments, "faucetAmount", "1000000000");
			return userMessage(
				"Help the user create a new Convex account with the signing service.\n\n" +
				"Use the `signingCreateAccount` tool with:\n" +
				"- passphrase: \"" + passphrase + "\"\n" +
				"- faucet: " + faucetAmount + "\n\n" +
				"After creation, explain:\n" +
				"- The account address (e.g. #42) — this is the on-chain identity\n" +
				"- The public key — this identifies the signing key stored server-side\n" +
				"- How to use `signingTransact` with this address and passphrase to execute transactions"
			);
		}
	}

	private class DeployContractPrompt extends McpPrompt {
		DeployContractPrompt() {
			super(McpPrompt.loadMetadata(PROMPTS_PATH + "deploy-contract.json"));
		}

		@Override
		public AVector<AMap<AString, ACell>> render(AMap<AString, ACell> arguments) {
			String source = getStringArg(arguments, "source", "(do nil)");
			String address = getStringArg(arguments, "address", null);
			String passphrase = getStringArg(arguments, "passphrase", null);
			return userMessage(
				"Help the user deploy a smart contract to Convex.\n\n" +
				"Use the `signingTransact` tool with:\n" +
				"- source: \"(deploy " + source + ")\"\n" +
				"- address: \"" + address + "\"\n" +
				"- passphrase: \"" + passphrase + "\"\n\n" +
				"The `deploy` function creates a new actor account and returns its address. " +
				"After deployment, use `describeAccount` on the returned address to show what was deployed."
			);
		}
	}

	private class TransferFundsPrompt extends McpPrompt {
		TransferFundsPrompt() {
			super(McpPrompt.loadMetadata(PROMPTS_PATH + "transfer-funds.json"));
		}

		@Override
		public AVector<AMap<AString, ACell>> render(AMap<AString, ACell> arguments) {
			String from = getStringArg(arguments, "from", null);
			String to = getStringArg(arguments, "to", null);
			String amount = getStringArg(arguments, "amount", null);
			String passphrase = getStringArg(arguments, "passphrase", null);
			return userMessage(
				"Help the user transfer Convex coins.\n\n" +
				"Use the `signingTransact` tool with:\n" +
				"- source: \"(transfer " + to + " " + amount + ")\"\n" +
				"- address: \"" + from + "\"\n" +
				"- passphrase: \"" + passphrase + "\"\n\n" +
				"After the transfer, use the `query` tool to check balances:\n" +
				"- source: \"(balance " + from + ")\" for the sender\n" +
				"- source: \"(balance " + to + ")\" for the recipient\n\n" +
				"Report the transfer result and updated balances."
			);
		}
	}

	// ==================== Helpers ====================

	private static String getStringArg(AMap<AString, ACell> arguments, String key, String defaultValue) {
		AString cell = RT.ensureString(arguments.get(Strings.create(key)));
		if (cell != null) return cell.toString();
		return defaultValue;
	}
}
