package convex.peer.tools;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import convex.api.Convex;
import convex.core.Result;
import convex.core.cpos.Belief;
import convex.core.cpos.Block;
import convex.core.cpos.CPoSConstants;
import convex.core.cpos.Order;
import convex.core.crypto.AKeyPair;
import convex.core.cvm.Address;
import convex.core.cvm.Context;
import convex.core.cvm.Keywords;
import convex.core.cvm.Migrations;
import convex.core.cvm.Peer;
import convex.core.cvm.PeerStatus;
import convex.core.cvm.State;
import convex.core.cvm.Symbols;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AMap;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Blob;
import convex.core.data.Hash;
import convex.core.data.Keyword;
import convex.core.data.SignedData;
import convex.core.data.Symbol;
import convex.core.data.prim.CVMBool;
import convex.core.data.Lists;
import convex.core.data.prim.CVMLong;
import convex.core.init.Init;
import convex.core.lang.Core;
import convex.core.lang.RT;
import convex.core.lang.Reader;
import convex.core.util.JSON;
import convex.core.util.Utils;
import convex.etch.EtchStore;
import convex.peer.API;

/**
 * Runnable rehearsal tool for network protocol upgrades against a live network.
 *
 * <p>Runs four phases, each with diagnostics designed to pin down exactly where a
 * divergence occurs:</p>
 *
 * <ol>
 * <li><b>SYNC</b> — connect to a peer (default {@code peer.convex.live:18888}), fetch
 *     status, and acquire the genesis state, current consensus state and belief (all
 *     Merkle-verified by hash on acquisition).</li>
 * <li><b>REPLAY</b> — re-execute the peer's ordering from genesis via
 *     {@link Peer#recalcState(long, long)} (the exact Peer startup path, ultimately
 *     applying entries through {@link State#applyBlock}) and check this release
 *     reproduces the peer's consensus state bit-for-bit. A mismatch is diffed down to
 *     the differing accounts and fields, and classified: divergence confined to cost
 *     accounting (balances/fees/juice/memory) is a warning while the live network is
 *     pre-v1 (cost changes ship un-gated under the tier-2 policy), whereas semantic
 *     divergence (code, data, keys, sequences, peer set) is always a failure.</li>
 * <li><b>MIGRATE</b> — apply this release's pending migrations directly to the live
 *     state ({@link Migrations#applyTo}) and validate the result: cell integrity,
 *     protocol version, semantic spot checks (new core functions, library fixes), and
 *     a fail-closed check of the exact state footprint permitted to migration v1.</li>
 * <li><b>BOUNDARY</b> — rehearse the real on-chain path on a doctored copy of the live
 *     state: a locally-keyed rehearsal peer is added (stake funded from an existing
 *     account, conserving the total coin balance), {@code schedule-upgrade} is invoked
 *     as a governance account exactly as the bootstrap transaction does (embedded
 *     cell — no binding exists before v1), then the activation boundary is crossed via
 *     {@link State#applyUpgrades(long)} and a signed block through
 *     {@link State#applyBlock}, confirming the upgrade applies identically to the
 *     direct migration and conserves the coin supply.</li>
 * </ol>
 *
 * <p>Nothing is ever signed with live keys: phases 1–3 are read-only computation, and
 * phase 4 uses locally generated keys in a local copy of the state. No transaction is
 * ever submitted to the remote peer.</p>
 *
 * <p>This is a manually runnable operational tool: the unit test suite exercises only
 * the offline phases (MIGRATE, BOUNDARY) against a local genesis and never touches
 * the network.</p>
 *
 * <p>A strict JSON audit report is written to {@code upgrade-rehearsal-report.json}
 * by default. Override it with {@code --report FILE}.</p>
 *
 * <p>Usage: {@code java -cp convex.jar convex.peer.tools.VerifyNetworkUpgrade
 * [host] [--report FILE]}</p>
 *
 * <p>Exit code 0 iff all checks pass without warnings; 1 means failure and 2 means
 * review is required because at least one warning was emitted.</p>
 */
public class VerifyNetworkUpgrade {

	static final String DEFAULT_HOST = "peer.convex.live:18888";
	static final long TIMEOUT_MILLIS = 60000;
	static final int MAX_DIFF_ITEMS = 20;

	static int failures = 0;
	static int warnings = 0;
	static Report report = new Report();

	public static void main(String[] args) throws Exception {
		String host = DEFAULT_HOST;
		boolean hostSet = false;
		Path reportPath = Path.of("upgrade-rehearsal-report.json");
		for (int i = 0; i < args.length; i++) {
			if ("--report".equals(args[i])) {
				if (++i >= args.length) throw new IllegalArgumentException("--report requires a file name");
				reportPath = Path.of(args[i]);
			} else if (args[i].startsWith("--")) {
				throw new IllegalArgumentException("Unknown option: " + args[i]);
			} else if (!hostSet) {
				host = args[i];
				hostSet = true;
			} else {
				throw new IllegalArgumentException("Only one host may be specified");
			}
		}
		failures = 0;
		warnings = 0;
		report = new Report();
		report.put("tool", VerifyNetworkUpgrade.class.getName());
		report.put("release", Utils.getVersion());
		report.put("commit", firstNonBlank(System.getProperty("convex.commit"), System.getenv("GITHUB_SHA")));
		report.put("artifactSha256", artifactSha256());
		report.put("java", System.getProperty("java.version"));
		report.put("os", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
		report.put("startedAt", Instant.now().toString());
		report.put("host", host);
		report.put("maxProtocolVersion", Migrations.MAX_VERSION);
		report.put("expectedLiveProtocolVersion", Migrations.LIVE_VERSION);
		info("Verifying network upgrade against: " + host);
		info("This release supports protocol version: " + Migrations.MAX_VERSION
				+ " (live network expected at: " + Migrations.LIVE_VERSION + ")");

		try {
			try (EtchStore store = EtchStore.createTemp("verify-upgrade")) {
				Convex convex = Convex.connect(host);
				convex.setStore(store);
				try {
					run(convex);
				} finally {
					convex.close();
				}
			}
		} catch (Throwable t) {
			fail("Unhandled rehearsal failure: " + t);
			t.printStackTrace(System.out);
		}

		info("");
		if (failures == 0) {
			info("ALL CHECKS PASSED" + ((warnings > 0) ? " (" + warnings + " warning(s))" : ""));
		} else {
			info(failures + " CHECK(S) FAILED" + ((warnings > 0) ? ", " + warnings + " warning(s)" : ""));
		}
		report.put("finishedAt", Instant.now().toString());
		report.put("failures", failures);
		report.put("warnings", warnings);
		report.put("ready", (failures == 0) && (warnings == 0));
		try {
			Files.writeString(reportPath, report.toJSON());
			info("Machine-readable report: " + reportPath.toAbsolutePath());
		} catch (Exception e) {
			fail("Could not write machine-readable report " + reportPath + ": " + e);
		}
		System.exit((failures > 0) ? 1 : ((warnings > 0) ? 2 : 0));
	}

	static void run(Convex convex) throws Exception {
		// ==================== Phase 1: SYNC ====================
		heading("Phase 1: SYNC");

		Result status = convex.requestStatusSync(TIMEOUT_MILLIS);
		if (status.isError()) {
			fail("Status request failed: " + status);
			return;
		}
		AMap<Keyword, ACell> smap = API.ensureStatusMap(status.getValue());
		if (smap == null) {
			fail("Bad status response: " + status);
			return;
		}

		Hash genesisHash = RT.ensureHash(smap.get(Keywords.GENESIS));
		Hash stateHash = RT.ensureHash(smap.get(Keywords.STATE));
		Hash beliefHash = RT.ensureHash(smap.get(Keywords.BELIEF));
		AccountKey remotePeerKey = RT.ensureAccountKey(smap.get(Keywords.PEER));
		CVMLong statePositionValue = RT.ensureLong(smap.get(Keywords.STATE_POSITION));
		CVMLong supportedVersion = RT.ensureLong(smap.get(Keywords.SUPPORTED_PROTOCOL_VERSION));
		Long remoteStatePosition = (statePositionValue == null) ? null : statePositionValue.longValue();
		report.put("remotePeerKey", remotePeerKey);
		report.put("genesisHash", genesisHash);
		report.put("remoteStateHash", stateHash);
		report.put("remoteStatePosition", remoteStatePosition);
		report.put("beliefHash", beliefHash);
		report.put("remoteSupportedProtocolVersion", supportedVersion);
		info("Remote peer key:  " + remotePeerKey);
		info("Genesis hash:     " + genesisHash);
		info("State hash:       " + stateHash);
		info("State position:   " + ((remoteStatePosition == null) ? "not advertised (legacy status)" : remoteStatePosition));
		info("Belief hash:      " + beliefHash);
		if (supportedVersion != null) info("Peer supports protocol version: " + supportedVersion);

		State genesis = acquire(convex, genesisHash, State.class, "genesis state");
		State remoteState = acquire(convex, stateHash, State.class, "consensus state");
		Belief belief = acquire(convex, beliefHash, Belief.class, "belief");
		if ((genesis == null) || (remoteState == null) || (belief == null)) return;

		validateCell(genesis, "genesis state");
		validateCell(remoteState, "consensus state");

		info("Genesis protocol version:   " + genesis.getProtocolVersion());
		info("Consensus protocol version: " + remoteState.getProtocolVersion());
		info("Consensus timestamp:        " + remoteState.getTimestamp());
		info("Accounts:                   " + remoteState.getAccounts().count());
		report.put("genesisProtocolVersion", genesis.getProtocolVersion());
		report.put("remoteProtocolVersion", remoteState.getProtocolVersion());
		report.put("remoteTimestamp", remoteState.getTimestamp().longValue());
		report.put("remoteAccountCount", remoteState.getAccounts().count());
		if (remoteState.getProtocolVersion() != Migrations.LIVE_VERSION) {
			fail("Live network at protocol version " + remoteState.getProtocolVersion()
					+ " but this release expects LIVE_VERSION=" + Migrations.LIVE_VERSION
					+ " — update Migrations.LIVE_VERSION (or connect to the intended network)");
		} else {
			pass("Live protocol version matches Migrations.LIVE_VERSION");
		}

		// ==================== Phase 2: REPLAY ====================
		heading("Phase 2: REPLAY");
		State replayed = replay(genesis, belief, remotePeerKey, remoteState, stateHash,remoteStatePosition);

		// ==================== Phase 3: MIGRATE ====================
		heading("Phase 3: MIGRATE");
		State pre = (replayed != null) ? replayed : remoteState;
		State upgraded = verifyMigration(pre);

		// ==================== Phase 4: BOUNDARY ====================
		heading("Phase 4: BOUNDARY");
		verifyBoundary(pre, upgraded);
	}

	/**
	 * Replays the remote peer's ordering from genesis using the consensus code path,
	 * verifying this release deterministically reproduces the live consensus state.
	 *
	 * @return The replayed state on success, null if replay failed or was skipped
	 */
	static State replay(State genesis, Belief belief, AccountKey remotePeerKey,
			State remoteState, Hash stateHash, Long advertisedStatePosition) {
		SignedData<Order> so = belief.getOrders().get(remotePeerKey);
		if (so == null) {
			fail("Remote peer's own order not present in acquired belief — cannot replay");
			return null;
		}
		Order order = so.getValue();
		long cp = order.getConsensusPoint(CPoSConstants.CONSENSUS_LEVEL_FINALITY);
		AVector<SignedData<Block>> blocks = order.getBlocks();
		info("Order: " + blocks.count() + " blocks, finality consensus point: " + cp);

		long targetPosition;
		if (advertisedStatePosition != null) {
			targetPosition=advertisedStatePosition;
			if ((targetPosition < 0) || (targetPosition > cp)) {
				fail("Advertised state position " + targetPosition
						+ " is outside the finalised Order range 0.." + cp);
				return null;
			}
		} else {
			// A legacy status does not identify which Order prefix produced its state.
			// Discover a matching prefix where possible, then verify it again through Peer.
			targetPosition=findLegacyStatePosition(genesis,blocks,cp,remoteState);
			if (targetPosition < 0) {
				targetPosition=cp;
				warn("Legacy status state was not found in the locally replayed finalised prefixes; "
						+ "using finality position " + cp + " for divergence diagnostics");
			} else {
				info("Derived legacy state position from matching replay prefix: " + targetPosition);
			}
		}

		long start = System.currentTimeMillis();
		State s;
		try {
			// Give a locally controlled Peer the remote Order, exactly as source startup does.
			AKeyPair replayKeyPair=AKeyPair.generate();
			SignedData<Order> replayOrder=replayKeyPair.signData(order);
			Belief replayBelief=belief.withOrders(
					belief.getOrders().assoc(replayKeyPair.getAccountKey(),replayOrder));
			Peer replayPeer=Peer.create(replayKeyPair,genesis,replayBelief);
			replayPeer=replayPeer.recalcState(0,targetPosition);
			s=replayPeer.getConsensusState();
		} catch (Throwable t) {
			fail("Peer replay threw while computing position " + targetPosition + ": " + t);
			t.printStackTrace(System.out);
			return null;
		}
		info("Replayed to position " + targetPosition + " through Peer startup path in "
				+ (System.currentTimeMillis() - start) + "ms");
		report.put("replayPosition", targetPosition);
		report.put("replayedStateHash", s.getHash());
		report.put("replayExact", s.equals(remoteState));

		if (s.equals(remoteState)) {
			pass("Replay reproduces the live consensus state exactly (hash " + s.getHash() + ")");
			return s;
		}

		info("Replayed state hash: " + s.getHash());
		info("Expected state hash: " + stateHash);
		boolean semantic = diffStates(remoteState, s, "live", "replayed");
		if (!semantic && (Migrations.LIVE_VERSION == 0)) {
			// Pre-v1, juice/fee/memory cost changes ship un-gated (tier-2 policy), so
			// re-pricing history under current code is expected. Only content matters.
			warn("Replay divergence is confined to fee/juice/memory accounting — expected "
					+ "pre-v1, where cost changes ship un-gated. Code and data content "
					+ "reproduced exactly.");
		} else if (!semantic) {
			fail("Replay diverges in cost accounting at protocol version "
					+ Migrations.LIVE_VERSION + " — post-v1 cost changes must be version-gated");
		} else {
			fail("Replay diverges SEMANTICALLY from the live consensus state (code/data "
					+ "content differs) — see diff above");
		}
		return null;
	}

	/**
	 * Finds an Order prefix matching a state from a legacy status response.
	 * Returns -1 if current code does not reproduce the remote state at any finalised prefix.
	 */
	static long findLegacyStatePosition(State genesis, AVector<SignedData<Block>> blocks,
			long finalityPoint, State remoteState) {
		State s=genesis;
		if (s.equals(remoteState)) return 0;
		for (long i=0; i<finalityPoint; i++) {
			try {
				s=s.applyBlock(blocks.get(i)).getState();
			} catch (Throwable t) {
				info("Legacy prefix discovery threw at position " + i + ": " + t);
				return -1;
			}
			if (s.equals(remoteState)) return i+1;
			if ((i > 0) && (i % 10000 == 0)) info("  ... inspected " + i + " legacy replay prefixes");
		}
		return -1;
	}

	/**
	 * Applies this release's pending migrations directly to the given state and
	 * verifies the result. Returns the upgraded state, or null on failure.
	 */
	static State verifyMigration(State pre) {
		long fromVersion = pre.getProtocolVersion();
		report.put("preMigrationHash", pre.getHash());
		report.put("preMigrationVersion", fromVersion);
		if (fromVersion >= Migrations.MAX_VERSION) {
			info("State already at protocol version " + fromVersion + " — no pending migrations to rehearse");
			return pre;
		}
		info("Applying migrations: version " + fromVersion + " -> " + Migrations.MAX_VERSION);

		State upgraded;
		try {
			upgraded = Migrations.applyTo(pre, Migrations.MAX_VERSION);
		} catch (Throwable t) {
			fail("Migration threw: " + t);
			t.printStackTrace(System.out);
			return null;
		}
		pass("Migrations applied without error");
		report.put("migratedStateHash", upgraded.getHash());
		report.put("migratedProtocolVersion", upgraded.getProtocolVersion());

		validateCell(upgraded, "upgraded state");

		if (upgraded.getProtocolVersion() == Migrations.MAX_VERSION) {
			pass("Upgraded state at protocol version " + Migrations.MAX_VERSION);
		} else {
			fail("Upgraded state at unexpected protocol version " + upgraded.getProtocolVersion());
		}

		long preTotal = pre.computeTotalBalance();
		long postTotal = upgraded.computeTotalBalance();
		report.put("preMigrationCoinSupply", preTotal);
		report.put("postMigrationCoinSupply", postTotal);
		if (preTotal == postTotal) {
			pass("Migration conserves the total coin balance (" + preTotal + ")");
		} else {
			fail("Migration changed the total coin balance: " + preTotal + " -> " + postTotal
					+ " (delta " + (postTotal - preTotal) + ")");
		}

		// Semantic spot checks: new core functions and library fixes live on the
		// REAL network state, not just test genesis
		evalCheck(upgraded, "(cat 0x12 0x34)", Blob.fromHex("1234"), "cat concatenates blobs");
		evalCheck(upgraded, "(symbol? (gensym))", CVMBool.TRUE, "gensym returns a symbol");
		evalCheck(upgraded,
				"(do (import convex.trust :as trust) "
						+ "(def m (deploy '(defn ^:callable check-trusted? [s a o] (fail :ASSERT \"boom\")))) "
						+ "(trust/trusted? m *address*))",
				CVMBool.FALSE, "trust/trusted? fails closed on a throwing monitor");
		containsCheck(upgraded,
				"(do (import convex.trust :as trust) (str (lookup-meta trust 'change-control)))",
				"(change-control", "set-control", "trust change-control docstring corrected");

		// Fail-closed footprint check: exact top-level state components and account
		// fields allowed by the migration, resolved against this network's CNS.
		info("Migration footprint (changed accounts):");
		diffStates(pre, upgraded, "pre-upgrade", "upgraded");
		String footprintError = migrationFootprintError(pre, upgraded);
		report.put("migrationFootprintValid", footprintError == null);
		if (footprintError == null) {
			pass("Migration changed only the approved v1 state footprint");
		} else {
			fail("Migration footprint violation: " + footprintError);
		}

		return upgraded;
	}

	/** CNS names whose actor code or metadata may be redefined by migration v1. */
	static final String[] V1_LIBRARY_NAMES = { "convex.fungible", "convex.asset",
			"asset.multi-token", "asset.nft.simple", "asset.nft.basic",
			"asset.box.actor", "convex.trust", "convex.trust.delegate" };

	/**
	 * Returns null iff the v1 migration changed only its declared state footprint.
	 * This is deliberately independent of object identity so it works on acquired,
	 * decoded network states as well as the in-memory genesis fixture.
	 */
	static String migrationFootprintError(State pre, State post) {
		if (pre.getProtocolVersion() != 0 || post.getProtocolVersion() != 1) {
			return "v1 footprint checker requires protocol versions 0 -> 1, got "
					+ pre.getProtocolVersion() + " -> " + post.getProtocolVersion();
		}
		if (pre.getAccounts().count() != post.getAccounts().count()) {
			return "account count changed: " + pre.getAccounts().count() + " -> "
					+ post.getAccounts().count();
		}
		if (!pre.getPeers().equals(post.getPeers())) return "peer records changed";
		if (!pre.getSchedule().equals(post.getSchedule())) return "scheduled operations changed";

		AVector<CVMLong> expectedUpgrades = pre.getUpgradeVector();
		for (long version = pre.getProtocolVersion(); version < post.getProtocolVersion(); version++) {
			expectedUpgrades = expectedUpgrades.conj(pre.getTimestamp());
		}
		State expectedGlobals = pre.withProtocolGlobals(post.getProtocolVersion(), expectedUpgrades);
		if (!expectedGlobals.getGlobals().equals(post.getGlobals())) {
			return "globals changed outside the protocol version/upgrade record";
		}

		Set<Address> allowed = new java.util.HashSet<>();
		allowed.add(Core.CORE_ADDRESS);
		for (String name : V1_LIBRARY_NAMES) {
			ACell resolved = pre.lookupCNS(name);
			if (resolved instanceof Address a) allowed.add(a);
		}
		long n = pre.getAccounts().count();
		for (long i = 0; i < n; i++) {
			convex.core.cvm.AccountStatus before = pre.getAccounts().get(i);
			convex.core.cvm.AccountStatus after = post.getAccounts().get(i);
			if (before.equals(after)) continue;
			Address address = Address.create(i);
			if (!allowed.contains(address)) return "unapproved account changed: " + address;
			convex.core.cvm.AccountStatus allowedAfter = before
					.withEnvironment(after.getEnvironment())
					.withMetadata(after.getMetadata());
			if (!allowedAfter.equals(after)) {
				return "account " + address + " changed outside environment/metadata:"
						+ accountDiff(before, after);
			}
		}
		return null;
	}

	/**
	 * Rehearses the on-chain upgrade path on a doctored copy of the given state: a
	 * locally-keyed rehearsal peer is ADDED to the peer set (its stake funded from an
	 * existing account, so the total coin balance is conserved), schedule-upgrade is
	 * invoked as a governance account exactly as the bootstrap transaction does, and
	 * the activation boundary is crossed for real.
	 */
	static void verifyBoundary(State pre, State upgraded) {
		if (pre.getProtocolVersion() >= Migrations.MAX_VERSION) {
			info("No pending migrations — boundary rehearsal skipped");
			return;
		}

		// Doctor a copy: ADD our own rehearsal peer (we cannot sign as a live peer).
		// Its stake is debited from an existing account so the total coin balance is
		// unchanged — peer stakes are part of the supply.
		AKeyPair peerKP = AKeyPair.generate();
		Address governance = Init.GOVERNANCE_ADDRESS;
		long stake = CPoSConstants.MINIMUM_EFFECTIVE_STAKE; // else checkBlock rejects our blocks
		Address funder = findFunder(pre, stake);
		if (funder == null) {
			fail("No user account with sufficient balance to fund the rehearsal peer stake");
			return;
		}
		State doctored = pre.putAccount(funder,
				pre.getAccount(funder).withBalance(pre.getAccount(funder).getBalance() - stake));
		doctored = doctored.withPeer(peerKP.getAccountKey(), PeerStatus.create(governance, stake));
		if (doctored.computeTotalBalance() == pre.computeTotalBalance()) {
			pass("Doctored rehearsal state conserves the total coin balance");
		} else {
			fail("Doctored rehearsal state changed the total coin balance: "
					+ pre.computeTotalBalance() + " -> " + doctored.computeTotalBalance());
		}

		long ts = doctored.getTimestamp().longValue();
		long activation = ts + 1000;

		// Schedule the upgrade as a governance account, exactly as the real bootstrap
		// transaction does: before v1 no schedule-upgrade binding exists in the core
		// environment, so the core function CELL is embedded directly in the evaluated
		// form (see UPGRADE.md). The cell is obtained from an upgraded core environment.
		ACell scheduleFn;
		try {
			State reference = (upgraded != null) ? upgraded : Migrations.applyAll(pre);
			scheduleFn = reference.getAccount(Init.CORE_ADDRESS).getEnvironment()
					.get(Symbols.SCHEDULE_UPGRADE);
		} catch (Throwable t) {
			fail("Could not obtain schedule-upgrade cell from an upgraded state: " + t);
			return;
		}
		if (scheduleFn == null) {
			fail("schedule-upgrade not present in the upgraded core environment");
			return;
		}
		Context ctx = Context.create(doctored, governance).eval(
				Lists.of(scheduleFn, CVMLong.create(activation)));
		if (ctx.isExceptional()) {
			fail("schedule-upgrade failed as governance account: " + ctx.getValue());
			return;
		}
		State scheduled = ctx.getState();
		report.put("boundaryScheduledHash", scheduled.getHash());
		report.put("boundaryActivation", activation);
		pass("schedule-upgrade accepted from governance account " + governance
				+ " (activation " + activation + ")");

		// Cross the boundary via the real upgrade function
		State afterDirect = scheduled.applyUpgrades(activation + 1);
		if (afterDirect.getProtocolVersion() == Migrations.MAX_VERSION) {
			pass("applyUpgrades crossed the boundary to protocol version " + Migrations.MAX_VERSION);
		} else {
			fail("applyUpgrades did not advance protocol version (still "
					+ afterDirect.getProtocolVersion() + ") — check upgrade scheduling/globals");
			return;
		}

		// Cross the boundary via full block application (as consensus would)
		SignedData<Block> block = peerKP.signData(Block.of(activation + 1));
		State afterBlock;
		try {
			afterBlock = scheduled.applyBlock(block).getState();
		} catch (Throwable t) {
			fail("applyBlock threw crossing the boundary: " + t);
			t.printStackTrace(System.out);
			return;
		}
		if (afterBlock.getProtocolVersion() == Migrations.MAX_VERSION) {
			report.put("boundaryStateHash", afterBlock.getHash());
			report.put("boundaryProtocolVersion", afterBlock.getProtocolVersion());
			pass("applyBlock crossed the boundary to protocol version " + Migrations.MAX_VERSION);
		} else {
			fail("applyBlock did not apply the scheduled upgrade (version "
					+ afterBlock.getProtocolVersion() + ")");
			return;
		}

		// The boundary path and the direct migration must produce identical core code
		if (upgraded != null) {
			ACell boundaryCore = afterBlock.getAccount(Init.CORE_ADDRESS).getEnvironment();
			ACell directCore = upgraded.getAccount(Init.CORE_ADDRESS).getEnvironment();
			if (boundaryCore.equals(directCore)) {
				pass("Boundary and direct migration produce an identical core environment");
			} else {
				fail("Boundary and direct migration DIVERGE in the core environment");
			}
		}

		if (afterBlock.computeTotalBalance() == doctored.computeTotalBalance()) {
			pass("Boundary crossing conserves the total coin balance");
		} else {
			fail("Boundary crossing changed the total coin balance: "
					+ doctored.computeTotalBalance() + " -> " + afterBlock.computeTotalBalance());
		}

		evalCheck(afterBlock, "(cat 0x12 0x34)", Blob.fromHex("1234"),
				"cat works after the boundary crossing");
	}

	/**
	 * Finds a user account (at or beyond the genesis user address, never a system
	 * account) able to fund the rehearsal peer stake.
	 */
	static Address findFunder(State s, long amount) {
		long n = s.getAccounts().count();
		for (long i = Init.GENESIS_ADDRESS.longValue(); i < n; i++) {
			if (s.getAccounts().get(i).getBalance() >= amount) return Address.create(i);
		}
		return null;
	}

	// ==================== Diagnostics ====================

	/** Divergence classes: cost accounting (fees/juice/memory) vs semantic content. */
	static final int D_COST = 1;
	static final int D_SEMANTIC = 2;

	/**
	 * Prints a component-by-component diff of two states, drilling into differing
	 * accounts and their fields, and classifies the divergence.
	 *
	 * @return true iff any SEMANTIC difference exists (code/data content, keys,
	 *         sequences, peer set) — as opposed to pure cost accounting (balances,
	 *         memory, fee/memory globals, peer stake records)
	 */
	static boolean diffStates(State expected, State actual, String expectedName, String actualName) {
		if (expected.equals(actual)) {
			info("  (states are identical)");
			return false;
		}
		int flags = 0;
		long ne = expected.getAccounts().count();
		long na = actual.getAccounts().count();
		if (ne != na) {
			info("  account counts differ: " + expectedName + "=" + ne + " " + actualName + "=" + na);
			flags |= D_SEMANTIC;
		}
		long n = Math.min(ne, na);
		int shown = 0;
		for (long i = 0; i < n; i++) {
			convex.core.cvm.AccountStatus ea = expected.getAccounts().get(i);
			convex.core.cvm.AccountStatus aa = actual.getAccounts().get(i);
			if (ea.equals(aa)) continue;
			flags |= accountDiffFlags(ea, aa);
			if (shown++ >= MAX_DIFF_ITEMS) continue;
			info("  account #" + i + " differs:" + accountDiff(ea, aa));
		}
		if (shown > MAX_DIFF_ITEMS) {
			info("  ... " + (shown - MAX_DIFF_ITEMS) + " further differing accounts suppressed");
		}
		if (!expected.getPeers().equals(actual.getPeers())) {
			boolean sameKeys = (expected.getPeers().count() == actual.getPeers().count());
			if (sameKeys) {
				for (long i = 0; i < expected.getPeers().count(); i++) {
					if (!expected.getPeers().entryAt(i).getKey()
							.equals(actual.getPeers().entryAt(i).getKey())) {
						sameKeys = false;
						break;
					}
				}
			}
			if (sameKeys) {
				info("  peer records differ (stakes/rewards/timestamps; same peer set)");
				flags |= D_COST;
			} else {
				info("  peer SETS differ: " + expectedName + "=" + expected.getPeers().count()
						+ " entries, " + actualName + "=" + actual.getPeers().count() + " entries");
				flags |= D_SEMANTIC;
			}
		}
		if (!expected.getGlobals().equals(actual.getGlobals())) {
			info("  globals differ:");
			info("    " + expectedName + ": " + expected.getGlobals());
			info("    " + actualName + ":  " + actual.getGlobals());
			// Timestamp, block number, protocol version and upgrade vector are semantic;
			// residual global differences (fees, memory pools) are cost accounting
			boolean semanticGlobals = !expected.getTimestamp().equals(actual.getTimestamp())
					|| (expected.getBlockNumber() != actual.getBlockNumber())
					|| (expected.getProtocolVersion() != actual.getProtocolVersion())
					|| !expected.getUpgradeVector().equals(actual.getUpgradeVector());
			flags |= semanticGlobals ? D_SEMANTIC : D_COST;
		}
		if (!expected.getSchedule().equals(actual.getSchedule())) {
			info("  schedules differ");
			flags |= D_SEMANTIC;
		}
		return (flags & D_SEMANTIC) != 0;
	}

	/** Classifies which divergence classes an account difference falls into. */
	static int accountDiffFlags(convex.core.cvm.AccountStatus a, convex.core.cvm.AccountStatus b) {
		int f = 0;
		if (a.getBalance() != b.getBalance()) f |= D_COST;
		if (a.getMemory() != b.getMemory()) f |= D_COST;
		if (a.getSequence() != b.getSequence()) f |= D_SEMANTIC;
		if (!java.util.Objects.equals(a.getAccountKey(), b.getAccountKey())) f |= D_SEMANTIC;
		if (!java.util.Objects.equals(a.getEnvironment(), b.getEnvironment())) f |= D_SEMANTIC;
		if (!java.util.Objects.equals(a.getMetadata(), b.getMetadata())) f |= D_SEMANTIC;
		if (!java.util.Objects.equals(a.getHoldings(), b.getHoldings())) f |= D_SEMANTIC;
		return f;
	}

	/** One-line summary of which fields of an account differ. */
	static String accountDiff(convex.core.cvm.AccountStatus a, convex.core.cvm.AccountStatus b) {
		StringBuilder sb = new StringBuilder();
		if (a.getBalance() != b.getBalance())
			sb.append(" balance(").append(a.getBalance()).append("->").append(b.getBalance()).append(")");
		if (a.getSequence() != b.getSequence())
			sb.append(" sequence(").append(a.getSequence()).append("->").append(b.getSequence()).append(")");
		if (!java.util.Objects.equals(a.getAccountKey(), b.getAccountKey()))
			sb.append(" key");
		if (!java.util.Objects.equals(a.getEnvironment(), b.getEnvironment())) {
			sb.append(" env(");
			sb.append(envDiffSymbols(a.getEnvironment(), b.getEnvironment()));
			sb.append(")");
		}
		if (!java.util.Objects.equals(a.getMetadata(), b.getMetadata()))
			sb.append(" metadata");
		if (!java.util.Objects.equals(a.getHoldings(), b.getHoldings()))
			sb.append(" holdings");
		if (a.getMemory() != b.getMemory())
			sb.append(" memory(").append(a.getMemory()).append("->").append(b.getMemory()).append(")");
		return (sb.length() == 0) ? " (unidentified field)" : sb.toString();
	}

	/** Names up to a few symbols whose bindings differ between two environments. */
	static String envDiffSymbols(AHashMap<Symbol, ACell> a, AHashMap<Symbol, ACell> b) {
		if (a == null) return "added";
		if (b == null) return "removed";
		StringBuilder sb = new StringBuilder();
		int shown = 0;
		long n = a.count();
		for (long i = 0; i < n; i++) {
			convex.core.data.MapEntry<Symbol, ACell> e = a.entryAt(i);
			ACell other = b.get(e.getKey());
			if (java.util.Objects.equals(e.getValue(), other)) continue;
			if (shown++ >= 5) { sb.append(",..."); break; }
			if (shown > 1) sb.append(",");
			sb.append(e.getKey());
		}
		long nb = b.count();
		for (long i = 0; (i < nb) && (shown < 5); i++) {
			convex.core.data.MapEntry<Symbol, ACell> e = b.entryAt(i);
			if (!a.containsKey(e.getKey())) {
				if (shown++ > 0) sb.append(",");
				sb.append("+").append(e.getKey());
			}
		}
		return sb.toString();
	}

	// ==================== Check helpers ====================

	@SuppressWarnings("unchecked")
	static <T extends ACell> T acquire(Convex convex, Hash hash, Class<T> type, String desc) {
		if (hash == null) {
			fail("No hash provided for " + desc);
			return null;
		}
		try {
			long start = System.currentTimeMillis();
			ACell cell = convex.acquire(hash).get(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
			info("Acquired " + desc + " in " + (System.currentTimeMillis() - start) + "ms"
					+ " (memory size " + cell.getMemorySize() + " bytes)");
			return (T) cell;
		} catch (Exception e) {
			fail("Failed to acquire " + desc + " (" + hash + "): " + e);
			return null;
		}
	}

	static void validateCell(ACell cell, String desc) {
		try {
			cell.validate();
			pass("Validated " + desc);
		} catch (Exception e) {
			fail("Validation FAILED for " + desc + ": " + e);
		}
	}

	/** Evaluates code locally against the state and checks the result. */
	static void evalCheck(State state, String code, ACell expected, String desc) {
		try {
			Context ctx = Context.create(state, Init.GENESIS_ADDRESS).eval(Reader.read(code));
			if (ctx.isExceptional()) {
				fail(desc + " — eval error: " + ctx.getValue() + " for: " + code);
			} else if (java.util.Objects.equals(expected, ctx.getResult())) {
				pass(desc);
			} else {
				fail(desc + " — expected " + expected + " but got " + ctx.getResult() + " for: " + code);
			}
		} catch (Throwable t) {
			fail(desc + " — threw: " + t + " for: " + code);
		}
	}

	/** Evaluates code and checks the string result contains/omits given fragments. */
	static void containsCheck(State state, String code, String mustContain, String mustNotContain, String desc) {
		try {
			Context ctx = Context.create(state, Init.GENESIS_ADDRESS).eval(Reader.read(code));
			if (ctx.isExceptional()) {
				fail(desc + " — eval error: " + ctx.getValue());
				return;
			}
			String s = String.valueOf(ctx.getResult());
			if (s.contains(mustContain) && !s.contains(mustNotContain)) {
				pass(desc);
			} else {
				fail(desc + " — got: " + s.substring(0, Math.min(200, s.length())));
			}
		} catch (Throwable t) {
			fail(desc + " — threw: " + t);
		}
	}

	// ==================== Output ====================

	static void heading(String s) {
		System.out.println();
		System.out.println("==== " + s + " ====");
	}

	static void info(String s) {
		System.out.println(s);
	}

	static void pass(String s) {
		report.record("pass", s);
		System.out.println("[PASS] " + s);
	}

	static void warn(String s) {
		warnings++;
		report.record("warning", s);
		System.out.println("[WARN] " + s);
	}

	static void fail(String s) {
		failures++;
		report.record("failure", s);
		System.out.println("[FAIL] " + s);
	}

	static String firstNonBlank(String... values) {
		for (String value : values) {
			if (value != null && !value.isBlank()) return value;
		}
		return null;
	}

	/** SHA-256 of the running jar when packaged; null for an exploded classes directory. */
	static String artifactSha256() {
		try {
			File source = new File(VerifyNetworkUpgrade.class.getProtectionDomain()
					.getCodeSource().getLocation().toURI());
			if (!source.isFile()) return null;
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source.toPath()));
			return java.util.HexFormat.of().formatHex(digest);
		} catch (Exception e) {
			return null;
		}
	}

	/** Minimal structured audit record kept alongside the human-readable diagnostics. */
	static final class Report {
		private final Map<String, Object> values = new LinkedHashMap<>();
		private final List<Map<String, Object>> checks = new ArrayList<>();

		void put(String key, Object value) {
			values.put(key, value);
		}

		void record(String status, String message) {
			Map<String, Object> check = new LinkedHashMap<>();
			check.put("status", status);
			check.put("message", message);
			checks.add(check);
		}

		String toJSON() {
			values.put("checks", checks);
			return JSON.toStringPretty(values);
		}
	}
}
