package convex.restapi.mcp;

import java.util.List;

import convex.api.Convex;
import convex.auth.ucan.UCAN;
import convex.core.Coin;
import convex.core.Result;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.ASignature;
import convex.core.cvm.AccountStatus;
import convex.core.cvm.Address;
import convex.core.cvm.transactions.ATransaction;
import convex.core.cvm.transactions.Invoke;
import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Blob;
import convex.core.data.Cells;
import convex.core.data.Maps;
import convex.core.data.Ref;
import convex.core.data.SignedData;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.lang.Reader;
import convex.peer.Server;
import convex.peer.signing.SigningService;
import convex.restapi.api.ABaseAPI;
import convex.restapi.auth.ConfirmationService;
import io.javalin.http.Context;

/**
 * MCP tools for the peer's signing service.
 *
 * Provides key management, signing, and JWT generation via the MCP JSON-RPC
 * protocol. Standard tools require authentication; elevated tools (import,
 * export, delete, change passphrase) additionally require user confirmation
 * via web browser.
 *
 * <h2>Standard Tools</h2>
 * <ul>
 *   <li>{@code signingServiceInfo} — signing service availability (no auth)</li>
 *   <li>{@code signingCreateKey} — generate new keypair</li>
 *   <li>{@code signingListKeys} — list registered public keys</li>
 *   <li>{@code signingSign} — sign arbitrary bytes</li>
 *   <li>{@code signingGetJWT} — create self-issued JWT</li>
 * </ul>
 *
 * <h2>Elevated Tools (require user confirmation)</h2>
 * <ul>
 *   <li>{@code signingImportKey} — import existing seed</li>
 *   <li>{@code signingExportKey} — reveal private seed</li>
 *   <li>{@code signingDeleteKey} — permanently destroy key</li>
 *   <li>{@code signingChangePassphrase} — re-encrypt with new passphrase</li>
 * </ul>
 *
 * @see SigningService
 * @see ConfirmationService
 */
class SigningMcpTools {

	private final McpAPI api;

	SigningMcpTools(McpAPI api) {
		this.api = api;
	}

	/**
	 * Registers all signing service tools with the MCP API.
	 */
	void registerAll() {
		api.registerTool(new SigningServiceInfoTool());
		api.registerTool(new SigningCreateKeyTool());
		api.registerTool(new SigningListKeysTool());
		api.registerTool(new SigningSignTool());
		api.registerTool(new SigningGetJWTTool());

		// Convenience tools
		api.registerTool(new SigningTransactTool());
		api.registerTool(new SigningCreateAccountTool());
		api.registerTool(new SigningListAccountsTool());
		api.registerTool(new SigningDelegateTool());

		// Elevated tools
		api.registerTool(new SigningImportKeyTool());
		api.registerTool(new SigningExportKeyTool());
		api.registerTool(new SigningDeleteKeyTool());
		api.registerTool(new SigningChangePassphraseTool());
	}

	// ==================== Helpers ====================

	private SigningService getSigningService() {
		return api.getRESTServer().getSigningService();
	}

	/**
	 * Handles the elevated confirmation flow. Returns null if the operation
	 * should proceed (confirmToken was valid and consumed). Otherwise returns
	 * the confirmation_required response or an error.
	 */
	private AMap<AString, ACell> handleElevated(String toolName, AMap<AString, ACell> arguments,
			AString identity, String description) {
		ConfirmationService confirmSvc = api.getRESTServer().getConfirmationService();
		AString confirmTokenCell = RT.ensureString(arguments.get(McpAPI.ARG_CONFIRM_TOKEN));

		if (confirmTokenCell != null) {
			String token = confirmTokenCell.toString();
			AMap<AString, ACell> stripped = arguments.dissoc(McpAPI.ARG_CONFIRM_TOKEN);
			if (confirmSvc.validateAndConsume(token, identity, toolName, stripped)) {
				return null; // proceed
			}
			return api.toolError("Invalid, expired, or already used confirmation token");
		}

		// No confirmToken — create a new confirmation
		AMap<AString, ACell> stripped = arguments.dissoc(McpAPI.ARG_CONFIRM_TOKEN);
		String token = confirmSvc.createConfirmation(identity, toolName, stripped, description);

		// Build confirmUrl from request context
		Context ctx = McpAPI.currentContext.get();
		String baseUrl = (ctx != null) ? ABaseAPI.getExternalBaseUrl(ctx, "") : "http://localhost";
		String confirmUrl = baseUrl + "/confirm?token=" + token;

		AMap<AString, ACell> result = Maps.of(
			"status", "confirmation_required",
			"confirmUrl", confirmUrl,
			"confirmToken", token
		);
		return api.toolSuccess(result);
	}

	// ==================== Standard Tools ====================

	private class SigningServiceInfoTool extends McpTool {
		SigningServiceInfoTool() {
			super(McpTool.loadMetadata("convex/restapi/mcp/tools/signingServiceInfo.json"));
		}

		@Override
		public AMap<AString, ACell> handle(AMap<AString, ACell> arguments) {
			SigningService svc = getSigningService();
			AMap<AString, ACell> result = Maps.of(
				"available", (svc != null) ? CVMBool.TRUE : CVMBool.FALSE
			);
			return api.toolSuccess(result);
		}
	}

	private class SigningCreateKeyTool extends McpTool {
		SigningCreateKeyTool() {
			super(McpTool.loadMetadata("convex/restapi/mcp/tools/signingCreateKey.json"));
		}

		@Override
		public AMap<AString, ACell> handle(AMap<AString, ACell> arguments) {
			AString identity = api.getRequestIdentity();
			if (identity == null) return api.toolError("Authentication required");

			SigningService svc = getSigningService();
			if (svc == null) return api.toolError("Signing service not available");

			AString passphrase = RT.ensureString(arguments.get(McpAPI.ARG_PASSPHRASE));
			if (passphrase == null) return api.toolError("signingCreateKey requires 'passphrase' string");

			try {
				AccountKey publicKey = svc.createKey(identity, passphrase);
				return api.toolSuccess(Maps.of("publicKey", publicKey.toString()));
			} catch (Exception e) {
				return api.toolError("Key creation failed: " + e.getMessage());
			}
		}
	}

	private class SigningListKeysTool extends McpTool {
		SigningListKeysTool() {
			super(McpTool.loadMetadata("convex/restapi/mcp/tools/signingListKeys.json"));
		}

		@Override
		public AMap<AString, ACell> handle(AMap<AString, ACell> arguments) {
			AString identity = api.getRequestIdentity();
			if (identity == null) return api.toolError("Authentication required");

			SigningService svc = getSigningService();
			if (svc == null) return api.toolError("Signing service not available");

			try {
				List<AccountKey> keys = svc.listKeys(identity);
				AVector<ACell> keyStrings = Vectors.empty();
				for (AccountKey ak : keys) {
					keyStrings = keyStrings.conj(Strings.create(ak.toString()));
				}
				return api.toolSuccess(Maps.of("keys", keyStrings));
			} catch (Exception e) {
				return api.toolError("List keys failed: " + e.getMessage());
			}
		}
	}

	private class SigningSignTool extends McpTool {
		SigningSignTool() {
			super(McpTool.loadMetadata("convex/restapi/mcp/tools/signingSign.json"));
		}

		@Override
		public AMap<AString, ACell> handle(AMap<AString, ACell> arguments) {
			AString identity = api.getRequestIdentity();
			if (identity == null) return api.toolError("Authentication required");

			SigningService svc = getSigningService();
			if (svc == null) return api.toolError("Signing service not available");

			AString publicKeyCell = RT.ensureString(arguments.get(McpAPI.ARG_PUBLIC_KEY));
			if (publicKeyCell == null) return api.toolError("signingSign requires 'publicKey' string");

			AString passphrase = RT.ensureString(arguments.get(McpAPI.ARG_PASSPHRASE));
			if (passphrase == null) return api.toolError("signingSign requires 'passphrase' string");

			AString valueCell = RT.ensureString(arguments.get(McpAPI.ARG_VALUE));
			if (valueCell == null) return api.toolError("signingSign requires 'value' hex string");

			AccountKey publicKey = AccountKey.parse(publicKeyCell.toString());
			if (publicKey == null) return api.toolError("Invalid public key format");

			Blob valueBlob = Blob.parse(valueCell.toString());
			if (valueBlob == null) return api.toolError("Value must be valid hex data");

			try {
				ASignature signature = svc.sign(identity, publicKey, passphrase, valueBlob);
				if (signature == null) return api.toolError("Key not found or wrong passphrase");

				return api.toolSuccess(Maps.of(
					"signature", signature.toHexString(),
					"publicKey", publicKeyCell
				));
			} catch (Exception e) {
				return api.toolError("Signing failed: " + e.getMessage());
			}
		}
	}

	private class SigningGetJWTTool extends McpTool {
		SigningGetJWTTool() {
			super(McpTool.loadMetadata("convex/restapi/mcp/tools/signingGetJWT.json"));
		}

		@Override
		public AMap<AString, ACell> handle(AMap<AString, ACell> arguments) {
			AString identity = api.getRequestIdentity();
			if (identity == null) return api.toolError("Authentication required");

			SigningService svc = getSigningService();
			if (svc == null) return api.toolError("Signing service not available");

			AString publicKeyCell = RT.ensureString(arguments.get(McpAPI.ARG_PUBLIC_KEY));
			if (publicKeyCell == null) return api.toolError("signingGetJWT requires 'publicKey' string");

			AString passphrase = RT.ensureString(arguments.get(McpAPI.ARG_PASSPHRASE));
			if (passphrase == null) return api.toolError("signingGetJWT requires 'passphrase' string");

			AccountKey publicKey = AccountKey.parse(publicKeyCell.toString());
			if (publicKey == null) return api.toolError("Invalid public key format");

			String audience = null;
			AString audienceCell = RT.ensureString(arguments.get(McpAPI.ARG_AUDIENCE));
			if (audienceCell != null) audience = audienceCell.toString();

			long lifetime = 3600;
			ACell lifetimeCell = arguments.get(McpAPI.ARG_LIFETIME);
			if (lifetimeCell != null) {
				CVMLong lifetimeLong = CVMLong.parse(lifetimeCell);
				if (lifetimeLong == null) return api.toolError("lifetime must be an integer");
				lifetime = lifetimeLong.longValue();
			}

			try {
				AString jwt = svc.getSelfSignedJWT(identity, publicKey, passphrase, audience, null, lifetime);
				if (jwt == null) return api.toolError("Key not found or wrong passphrase");
				return api.toolSuccess(Maps.of("jwt", jwt));
			} catch (Exception e) {
				return api.toolError("JWT creation failed: " + e.getMessage());
			}
		}
	}

	// ==================== Convenience Tools ====================

	/**
	 * Parses an address from a string, supporting "#13" and "13" formats.
	 */
	private static Address parseAddress(AString addressCell) {
		if (addressCell == null) return null;
		try {
			ACell parsed = Reader.read(addressCell.toString());
			if (parsed instanceof Address a) return a;
			CVMLong num = RT.ensureLong(parsed);
			if (num != null) return Address.create(num.longValue());
			return null;
		} catch (Exception e) {
			return null;
		}
	}

	private class SigningTransactTool extends McpTool {
		SigningTransactTool() {
			super(McpTool.loadMetadata("convex/restapi/mcp/tools/signingTransact.json"));
		}

		@Override
		public AMap<AString, ACell> handle(AMap<AString, ACell> arguments) {
			AString identity = api.getRequestIdentity();
			if (identity == null) return api.toolError("Authentication required");

			SigningService svc = getSigningService();
			if (svc == null) return api.toolError("Signing service not available");

			AString sourceCell = RT.ensureString(arguments.get(McpAPI.ARG_SOURCE));
			if (sourceCell == null) return api.toolError("signingTransact requires 'source' string");

			AString addressCell = RT.ensureString(arguments.get(McpAPI.ARG_ADDRESS));
			if (addressCell == null) return api.toolError("signingTransact requires 'address' string");

			AString passphrase = RT.ensureString(arguments.get(McpAPI.ARG_PASSPHRASE));
			if (passphrase == null) return api.toolError("signingTransact requires 'passphrase' string");

			Address address = parseAddress(addressCell);
			if (address == null) return api.toolError("Invalid address format");

			// Get account's on-chain key
			Server srv = api.getRESTServer().getServer();
			AccountStatus as = srv.getState().getAccount(address);
			if (as == null) return api.toolError("Account not found: " + address);

			AccountKey accountKey = as.getAccountKey();
			if (accountKey == null) return api.toolError("Account has no public key (actor account)");

			// Parse source
			ACell code;
			try {
				code = Reader.read(sourceCell.toString());
			} catch (Exception e) {
				return api.toolError("Failed to parse source: " + e.getMessage());
			}

			try {
				// Build transaction
				long sequence = as.getSequence() + 1;
				ATransaction transaction = Invoke.create(address, sequence, code);
				transaction = Cells.persist(transaction, srv.getStore());
				Ref<ATransaction> ref = transaction.getRef();
				Blob message = SignedData.getMessageForRef(ref);

				// Sign via signing service
				ASignature signature = svc.sign(identity, accountKey, passphrase, message);
				if (signature == null) return api.toolError("Key not found or wrong passphrase for account key " + accountKey);

				// Submit
				SignedData<ATransaction> signed = SignedData.create(accountKey, signature, ref);
				Result result = api.getRESTServer().getConvex().transactSync(signed);
				return api.toolResult(result);
			} catch (Exception e) {
				return api.toolError("Transaction failed: " + e.getMessage());
			}
		}
	}

	private class SigningCreateAccountTool extends McpTool {
		SigningCreateAccountTool() {
			super(McpTool.loadMetadata("convex/restapi/mcp/tools/signingCreateAccount.json"));
		}

		@Override
		public AMap<AString, ACell> handle(AMap<AString, ACell> arguments) {
			AString identity = api.getRequestIdentity();
			if (identity == null) return api.toolError("Authentication required");

			SigningService svc = getSigningService();
			if (svc == null) return api.toolError("Signing service not available");

			AString passphrase = RT.ensureString(arguments.get(McpAPI.ARG_PASSPHRASE));
			if (passphrase == null) return api.toolError("signingCreateAccount requires 'passphrase' string");

			Convex faucetClient = api.getRESTServer().getFaucet();
			if (faucetClient == null) return api.toolError("Faucet not available on this server");

			try {
				// Create key in signing service
				AccountKey publicKey = svc.createKey(identity, passphrase);

				// Resolve controller: default *caller* (faucet address), "nil" for self-sovereign
				AString controllerCell = RT.ensureString(arguments.get(McpAPI.ARG_CONTROLLER));
				String controllerStr = (controllerCell != null) ? controllerCell.toString() : "*caller*";

				String controllerCVM;
				if ("nil".equals(controllerStr)) {
					controllerCVM = null;
				} else if ("*caller*".equals(controllerStr)) {
					controllerCVM = "#" + faucetClient.getAddress().longValue();
				} else {
					Address cAddr = Address.parse(controllerStr);
					if (cAddr == null) return api.toolError("Invalid controller address: " + controllerStr);
					controllerCVM = "#" + cAddr.longValue();
				}

				// Build CVM source using deploy pattern
				String source;
				if (controllerCVM != null) {
					source = "(deploy '(do (set-controller " + controllerCVM + ") (set-key 0x" + publicKey.toHexString() + ")))";
				} else {
					source = "(create-account 0x" + publicKey.toHexString() + ")";
				}

				// Add faucet transfer if requested
				ACell faucetCell = arguments.get(McpAPI.ARG_FAUCET);
				if (faucetCell != null) {
					CVMLong faucetLong = CVMLong.parse(faucetCell);
					if (faucetLong == null) return api.toolError("Faucet amount must be a valid number");
					long max = api.getRESTServer().getFaucetMax();
					long amt = Math.min(faucetLong.longValue(), max);
					source = "(let [addr " + source + "] (transfer addr " + amt + ") addr)";
				}

				Result r = faucetClient.transactSync(source);
				if (r.isError()) return api.toolResult(r);

				Address address = (Address)r.getValue();
				AMap<AString, ACell> out = Maps.of(
					"address", CVMLong.create(address.longValue()),
					"publicKey", publicKey.toString()
				);
				ACell info = r.getInfo();
				if (info != null) out = out.assoc(McpAPI.KEY_INFO, info);
				return api.toolSuccess(out);
			} catch (Exception e) {
				return api.toolError("Account creation failed: " + e.getMessage());
			}
		}
	}

	// TODO: signingListAccounts should resolve on-chain addresses per key
	// when the peer has a proper key→account index. For now, returns keys only.
	private class SigningListAccountsTool extends McpTool {
		SigningListAccountsTool() {
			super(McpTool.loadMetadata("convex/restapi/mcp/tools/signingListAccounts.json"));
		}

		@Override
		public AMap<AString, ACell> handle(AMap<AString, ACell> arguments) {
			AString identity = api.getRequestIdentity();
			if (identity == null) return api.toolError("Authentication required");

			SigningService svc = getSigningService();
			if (svc == null) return api.toolError("Signing service not available");

			try {
				List<AccountKey> keys = svc.listKeys(identity);
				AVector<ACell> entries = Vectors.empty();
				for (AccountKey key : keys) {
					entries = entries.conj(Maps.of(
						"publicKey", key.toString()
					));
				}
				return api.toolSuccess(Maps.of("accounts", entries));
			} catch (Exception e) {
				return api.toolError("List accounts failed: " + e.getMessage());
			}
		}
	}

	private class SigningDelegateTool extends McpTool {
		private static final AString ARG_UCAN = Strings.intern("ucan");

		SigningDelegateTool() {
			super(McpTool.loadMetadata("convex/restapi/mcp/tools/signingDelegate.json"));
		}

		@Override
		public AMap<AString, ACell> handle(AMap<AString, ACell> arguments) {
			AString identity = api.getRequestIdentity();
			if (identity == null) return api.toolError("Authentication required");

			SigningService svc = getSigningService();
			if (svc == null) return api.toolError("Signing service not available");

			AString publicKeyCell = RT.ensureString(arguments.get(McpAPI.ARG_PUBLIC_KEY));
			if (publicKeyCell == null) return api.toolError("signingDelegate requires 'publicKey' string");

			AString passphrase = RT.ensureString(arguments.get(McpAPI.ARG_PASSPHRASE));
			if (passphrase == null) return api.toolError("signingDelegate requires 'passphrase' string");

			AccountKey publicKey = AccountKey.parse(publicKeyCell.toString());
			if (publicKey == null) return api.toolError("Invalid public key format");

			// Extract nested ucan object
			@SuppressWarnings("unchecked")
			AMap<AString, ACell> ucanArgs = RT.ensureMap(arguments.get(ARG_UCAN));
			if (ucanArgs == null) return api.toolError("signingDelegate requires 'ucan' object");

			// Parse aud (required) — accept did:key or hex public key
			AString audCell = RT.ensureString(ucanArgs.get(UCAN.AUD));
			if (audCell == null) return api.toolError("ucan.aud is required");

			AccountKey audienceKey;
			String audStr = audCell.toString();
			if (audStr.startsWith("did:key:")) {
				audienceKey = UCAN.fromDIDKey(audCell);
			} else {
				audienceKey = AccountKey.parse(audStr);
			}
			if (audienceKey == null) return api.toolError("Invalid aud: must be a did:key string or hex public key");

			// Parse exp (required)
			CVMLong expCell = RT.ensureLong(ucanArgs.get(UCAN.EXP));
			if (expCell == null) return api.toolError("ucan.exp is required (integer, unix seconds)");
			long expiry = expCell.longValue();

			// Parse optional fields
			Long notBefore = null;
			CVMLong nbfCell = RT.ensureLong(ucanArgs.get(UCAN.NBF));
			if (nbfCell != null) notBefore = nbfCell.longValue();

			@SuppressWarnings("unchecked")
			AVector<ACell> att = RT.ensureVector(ucanArgs.get(UCAN.ATT));
			@SuppressWarnings("unchecked")
			AVector<ACell> prf = RT.ensureVector(ucanArgs.get(UCAN.PRF));
			ACell fct = ucanArgs.get(UCAN.FCT);

			try {
				// Build payload and sign via signing service
				AMap<AString, ACell> payload = UCAN.buildPayload(
					publicKey, audienceKey, expiry, notBefore, att, prf, fct);
				Blob message = Ref.get(payload).getEncoding();
				ASignature signature = svc.sign(identity, publicKey, passphrase, message);
				if (signature == null) return api.toolError("Key not found or wrong passphrase");

				// Construct UCAN and build response
				UCAN ucan = UCAN.fromPayload(payload, signature);
				AMap<AString, ACell> tokenMap = ucan.toMap();
				Blob cad3 = Cells.encode(tokenMap);
				String hashHex = Cells.getHash(tokenMap).toHexString();

				return api.toolSuccess(Maps.of(
					"token", tokenMap,
					"cad3", cad3.toString(),
					"hash", hashHex
				));
			} catch (Exception e) {
				return api.toolError("Delegation failed: " + e.getMessage());
			}
		}
	}

	// ==================== Elevated Tools ====================

	private class SigningImportKeyTool extends McpTool {
		SigningImportKeyTool() {
			super(McpTool.loadMetadata("convex/restapi/mcp/tools/signingImportKey.json"));
		}

		@Override
		public AMap<AString, ACell> handle(AMap<AString, ACell> arguments) {
			AString identity = api.getRequestIdentity();
			if (identity == null) return api.toolError("Authentication required");

			SigningService svc = getSigningService();
			if (svc == null) return api.toolError("Signing service not available");

			AString seedCell = RT.ensureString(arguments.get(McpAPI.ARG_SEED));
			if (seedCell == null) return api.toolError("signingImportKey requires 'seed' string");

			AString passphrase = RT.ensureString(arguments.get(McpAPI.ARG_PASSPHRASE));
			if (passphrase == null) return api.toolError("signingImportKey requires 'passphrase' string");

			Blob seedBlob = Blob.parse(seedCell.toString());
			if (seedBlob == null || seedBlob.count() != AKeyPair.SEED_LENGTH) {
				return api.toolError("Seed must be a 32-byte hex string (64 hex characters)");
			}

			AMap<AString, ACell> confirmResult = handleElevated("signingImportKey", arguments,
				identity, "Import an Ed25519 key into the signing service");
			if (confirmResult != null) return confirmResult;

			try {
				AccountKey publicKey = svc.importKey(identity, seedBlob, passphrase);
				return api.toolSuccess(Maps.of("publicKey", publicKey.toString()));
			} catch (Exception e) {
				return api.toolError("Import failed: " + e.getMessage());
			}
		}
	}

	private class SigningExportKeyTool extends McpTool {
		SigningExportKeyTool() {
			super(McpTool.loadMetadata("convex/restapi/mcp/tools/signingExportKey.json"));
		}

		@Override
		public AMap<AString, ACell> handle(AMap<AString, ACell> arguments) {
			AString identity = api.getRequestIdentity();
			if (identity == null) return api.toolError("Authentication required");

			SigningService svc = getSigningService();
			if (svc == null) return api.toolError("Signing service not available");

			AString publicKeyCell = RT.ensureString(arguments.get(McpAPI.ARG_PUBLIC_KEY));
			if (publicKeyCell == null) return api.toolError("signingExportKey requires 'publicKey' string");

			AString passphrase = RT.ensureString(arguments.get(McpAPI.ARG_PASSPHRASE));
			if (passphrase == null) return api.toolError("signingExportKey requires 'passphrase' string");

			AccountKey publicKey = AccountKey.parse(publicKeyCell.toString());
			if (publicKey == null) return api.toolError("Invalid public key format");

			AMap<AString, ACell> confirmResult = handleElevated("signingExportKey", arguments,
				identity, "Export the private seed for key " + publicKeyCell);
			if (confirmResult != null) return confirmResult;

			try {
				ABlob seed = svc.exportKey(identity, publicKey, passphrase);
				if (seed == null) return api.toolError("Key not found or wrong passphrase");
				return api.toolSuccess(Maps.of("seed", seed.toString(), "publicKey", publicKeyCell));
			} catch (Exception e) {
				return api.toolError("Export failed: " + e.getMessage());
			}
		}
	}

	private class SigningDeleteKeyTool extends McpTool {
		SigningDeleteKeyTool() {
			super(McpTool.loadMetadata("convex/restapi/mcp/tools/signingDeleteKey.json"));
		}

		@Override
		public AMap<AString, ACell> handle(AMap<AString, ACell> arguments) {
			AString identity = api.getRequestIdentity();
			if (identity == null) return api.toolError("Authentication required");

			SigningService svc = getSigningService();
			if (svc == null) return api.toolError("Signing service not available");

			AString publicKeyCell = RT.ensureString(arguments.get(McpAPI.ARG_PUBLIC_KEY));
			if (publicKeyCell == null) return api.toolError("signingDeleteKey requires 'publicKey' string");

			AString passphrase = RT.ensureString(arguments.get(McpAPI.ARG_PASSPHRASE));
			if (passphrase == null) return api.toolError("signingDeleteKey requires 'passphrase' string");

			AccountKey publicKey = AccountKey.parse(publicKeyCell.toString());
			if (publicKey == null) return api.toolError("Invalid public key format");

			AMap<AString, ACell> confirmResult = handleElevated("signingDeleteKey", arguments,
				identity, "Permanently delete key " + publicKeyCell);
			if (confirmResult != null) return confirmResult;

			try {
				svc.deleteKey(identity, publicKey, passphrase);
				return api.toolSuccess(Maps.of("deleted", CVMBool.TRUE));
			} catch (Exception e) {
				return api.toolError("Delete failed: " + e.getMessage());
			}
		}
	}

	private class SigningChangePassphraseTool extends McpTool {
		SigningChangePassphraseTool() {
			super(McpTool.loadMetadata("convex/restapi/mcp/tools/signingChangePassphrase.json"));
		}

		@Override
		public AMap<AString, ACell> handle(AMap<AString, ACell> arguments) {
			AString identity = api.getRequestIdentity();
			if (identity == null) return api.toolError("Authentication required");

			SigningService svc = getSigningService();
			if (svc == null) return api.toolError("Signing service not available");

			AString publicKeyCell = RT.ensureString(arguments.get(McpAPI.ARG_PUBLIC_KEY));
			if (publicKeyCell == null) return api.toolError("signingChangePassphrase requires 'publicKey' string");

			AString passphrase = RT.ensureString(arguments.get(McpAPI.ARG_PASSPHRASE));
			if (passphrase == null) return api.toolError("signingChangePassphrase requires 'passphrase' string");

			AString newPassphrase = RT.ensureString(arguments.get(McpAPI.ARG_NEW_PASSPHRASE));
			if (newPassphrase == null) return api.toolError("signingChangePassphrase requires 'newPassphrase' string");

			AccountKey publicKey = AccountKey.parse(publicKeyCell.toString());
			if (publicKey == null) return api.toolError("Invalid public key format");

			AMap<AString, ACell> confirmResult = handleElevated("signingChangePassphrase", arguments,
				identity, "Change passphrase for key " + publicKeyCell);
			if (confirmResult != null) return confirmResult;

			try {
				svc.changePassphrase(identity, publicKey, passphrase, newPassphrase);
				return api.toolSuccess(Maps.of("updated", CVMBool.TRUE));
			} catch (IllegalArgumentException e) {
				return api.toolError("Wrong current passphrase");
			} catch (Exception e) {
				return api.toolError("Change passphrase failed: " + e.getMessage());
			}
		}
	}
}
