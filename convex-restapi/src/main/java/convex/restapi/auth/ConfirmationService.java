package convex.restapi.auth;

import java.security.SecureRandom;
import java.util.concurrent.ConcurrentHashMap;

import convex.core.crypto.Hashing;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Hash;
import convex.core.data.Strings;

/**
 * In-memory store for elevated operation confirmation tokens.
 *
 * When an agent calls an elevated signing service MCP tool (signingImportKey,
 * signingExportKey, signingDeleteKey, signingChangePassphrase) without a valid
 * confirmToken, the tool returns a {@code confirmation_required} response with
 * a {@code confirmUrl} and {@code confirmToken}. The user visits the
 * confirmUrl in a browser and approves the action. The agent then retries the
 * tool call with the confirmToken.
 *
 * <h2>Token Properties</h2>
 * <ul>
 *   <li><b>Single-use</b> — consumed on successful validation</li>
 *   <li><b>Time-limited</b> — 5-minute lifetime</li>
 *   <li><b>Scope-bound</b> — tied to specific identity, tool, and parameters</li>
 *   <li><b>Ephemeral</b> — not persisted; server restart invalidates all</li>
 * </ul>
 */
public class ConfirmationService {

	static final long DEFAULT_LIFETIME_MS = 5 * 60 * 1000; // 5 minutes
	private static final String TOKEN_PREFIX = "ct_";

	private final ConcurrentHashMap<String, Confirmation> confirmations = new ConcurrentHashMap<>();
	private final SecureRandom random = new SecureRandom();

	/**
	 * A pending or approved confirmation request.
	 */
	public record Confirmation(
		AString identity,
		String toolName,
		Hash paramsHash,
		String description,
		long expiresAt,
		boolean approved
	) {}

	/**
	 * Creates a new confirmation request.
	 *
	 * @param identity The authenticated identity requesting the operation
	 * @param toolName The MCP tool name
	 * @param params The tool parameters (confirmToken excluded)
	 * @param description Human-readable description for the confirmation page
	 * @return The confirmation token
	 */
	public String createConfirmation(AString identity, String toolName,
			AMap<AString, ACell> params, String description) {
		cleanup();
		String token = generateToken();
		Hash paramsHash = computeParamsHash(toolName, params);
		confirmations.put(token, new Confirmation(
			identity, toolName, paramsHash, description,
			System.currentTimeMillis() + DEFAULT_LIFETIME_MS, false
		));
		return token;
	}

	/**
	 * Approves a confirmation. Called when the user clicks "Confirm" in the browser.
	 *
	 * @param token The confirmation token
	 * @return true if the confirmation was found and approved
	 */
	public boolean approveConfirmation(String token) {
		Confirmation c = confirmations.get(token);
		if (c == null) return false;
		if (System.currentTimeMillis() > c.expiresAt) {
			confirmations.remove(token);
			return false;
		}
		confirmations.put(token, new Confirmation(
			c.identity, c.toolName, c.paramsHash, c.description, c.expiresAt, true
		));
		return true;
	}

	/**
	 * Validates and consumes a confirmation token. Single-use: the token
	 * is removed on successful validation.
	 *
	 * @param token The confirmation token
	 * @param identity The current authenticated identity
	 * @param toolName The tool being called
	 * @param params The tool parameters (confirmToken excluded)
	 * @return true if the confirmation is valid, approved, and matches
	 */
	public boolean validateAndConsume(String token, AString identity, String toolName,
			AMap<AString, ACell> params) {
		Confirmation c = confirmations.remove(token);
		if (c == null) return false;
		if (System.currentTimeMillis() > c.expiresAt) return false;
		if (!c.approved) return false;
		if (!c.identity.equals(identity)) return false;
		if (!c.toolName.equals(toolName)) return false;
		Hash paramsHash = computeParamsHash(toolName, params);
		return c.paramsHash.equals(paramsHash);
	}

	/**
	 * Gets the confirmation details for display on the confirmation page.
	 * Returns null if the token is invalid or expired.
	 */
	public Confirmation getConfirmation(String token) {
		Confirmation c = confirmations.get(token);
		if (c == null) return null;
		if (System.currentTimeMillis() > c.expiresAt) {
			confirmations.remove(token);
			return null;
		}
		return c;
	}

	/**
	 * Returns the number of active (non-expired) confirmations.
	 */
	public int size() {
		cleanup();
		return confirmations.size();
	}

	private void cleanup() {
		long now = System.currentTimeMillis();
		confirmations.entrySet().removeIf(e -> now > e.getValue().expiresAt);
	}

	private String generateToken() {
		byte[] bytes = new byte[16];
		random.nextBytes(bytes);
		StringBuilder sb = new StringBuilder(TOKEN_PREFIX);
		for (byte b : bytes) {
			sb.append(String.format("%02x", b & 0xFF));
		}
		return sb.toString();
	}

	/**
	 * Computes a hash of the tool name and parameters for scope binding.
	 */
	static Hash computeParamsHash(String toolName, AMap<AString, ACell> params) {
		AString combined = Strings.create(toolName + ":" + params.toString());
		return Hashing.sha256(combined);
	}
}
