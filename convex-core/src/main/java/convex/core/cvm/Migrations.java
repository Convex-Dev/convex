package convex.core.cvm;

import java.io.IOException;
import java.util.List;

import convex.core.Constants;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AList;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Symbol;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.Core;
import convex.core.lang.Reader;
import convex.core.util.Utils;

/**
 * Registry of network upgrade migrations.
 *
 * <p>Migrations are bound positionally to protocol versions: {@code get(k)} is the
 * migration producing protocol version {@code k+1}. Migrations are not named — the
 * version number is their identity, and an upgrade's entire on-chain footprint is a
 * single activation timestamp in the upgrade vector. See UPGRADE.md.</p>
 *
 * <p>The list is append-only, forever: replay from genesis requires every historical
 * migration, so old migrations remain compiled into every future release. A release
 * supports protocol versions up to {@link #MAX_VERSION}; a due version beyond
 * that is a missing migration and causes the peer to withdraw from consensus.</p>
 */
public class Migrations {

	/**
	 * A network upgrade migration: the state transition applied when a scheduled
	 * upgrade activates, immediately before its protocol version increment.
	 *
	 * <p><b>Purity is a hard contract.</b> A migration must be a pure function of the
	 * pre-state: same pre-state, same post-state, bit-identical on every peer. No
	 * clock, no randomness, no I/O, no external state. A migration is not bound by
	 * Juice, account permissions or signature checks, and may mutate any part of the
	 * State — including core libraries and global constants. An impure migration
	 * forks the network.</p>
	 *
	 * <p>A migration is ordinary source code in the peer release and may reference
	 * any new source shipped alongside it (e.g. a recompiled core function).</p>
	 */
	@FunctionalInterface
	public interface Migration {
		/**
		 * Apply this migration to a State.
		 * @param preState State immediately before the upgrade activates
		 * @return Migrated State
		 */
		State apply(State preState);
	}

	/**
	 * A migration that evaluates CVM forms from a classpath resource in the context
	 * of a given account, mirroring how genesis loads {@code core.cvx}. Used to
	 * redefine core (or actor) functions from a resource kept separate from the
	 * genesis sources, so the genesis hash is unchanged. See UPGRADE.md.
	 */
	static final class CodeMigration implements Migration {
		private final Address account;
		private final AList<ACell> forms;

		CodeMigration(Address account, String resource) {
			this.account = account;
			this.forms = readResource(resource);
		}

		@Override
		public State apply(State preState) {
			return evalForms(preState, account, forms);
		}
	}

	/** Reads all CVM forms from a classpath resource (fails class init if missing). */
	static AList<ACell> readResource(String resource) {
		try {
			return Reader.readAll(Utils.readResourceAsString(resource));
		} catch (IOException e) {
			throw new ExceptionInInitializerError("Missing migration resource: " + resource);
		}
	}

	/**
	 * Evaluates CVM forms in the context of an account, returning the updated State.
	 * Not bound by juice (uses the maximum limit), mirroring how genesis loads code.
	 */
	static State evalForms(State preState, Address account, AList<ACell> forms) {
		Context ctx = Context.create(preState, account, Constants.MAX_TRANSACTION_JUICE);
		for (ACell form : forms) {
			ctx = ctx.expandCompile(form);
			if (ctx.isExceptional()) throw new IllegalStateException("Migration compile failed for " + form + ": " + ctx.getValue());
			ctx = ctx.exec((AOp<?>) ctx.getResult());
			if (ctx.isExceptional()) throw new IllegalStateException("Migration exec failed for " + form + ": " + ctx.getValue());
		}
		return ctx.getState();
	}

	/**
	 * v1 — the genesis upgrade. A network at protocol version 0 upgrades to protocol version 1 in a
	 * single step that both installs the mechanism and fixes every bug known at
	 * this point, so activating the upgrade mechanism brings a network fully up to
	 * date without leaving known bugs for a later upgrade. Specifically it:
	 *
	 * <ol>
	 * <li>installs the {@code schedule-upgrade} / {@code unschedule-upgrade} /
	 * {@code gensym} / {@code cat} / {@code splice} core bindings (CoreFn singletons,
	 * marked {@code :static}; these cannot be defined in Lisp), enabling future
	 * upgrades to be scheduled on-chain, macros to introduce hygienic bindings, and
	 * raw concatenation and positional overwrite of BlobLike values — their
	 * {@code :doc} metadata is applied by the metadata step below; and</li>
	 * <li>applies the known fixes: #533 ({@code update} / {@code update-in} in core),
	 * #600 (core docstring corrections), #528 ({@code add-mint} in {@code convex.fungible}),
	 * #621 ({@code owns?} map form in {@code convex.asset}) and #620 ({@code offer} in
	 * {@code asset.multi-token}).</li>
	 * </ol>
	 *
	 * <p>The protocol globals already exist when this fires: they were created by
	 * the governance transaction that scheduled this upgrade (which embedded the
	 * schedule-upgrade cell directly, since no binding existed yet). See UPGRADE.md.</p>
	 */
	static final class Bootstrap implements Migration {
		/** Core function fixes applied as part of v1 (#533: update / update-in). */
		private static final CodeMigration CORE_FIXES =
				new CodeMigration(Core.CORE_ADDRESS, "/convex/migrations/v1-core.cvx");
		/** Core docstring corrections applied as part of v1 (#600). */
		private static final CodeMigration META_FIXES =
				new CodeMigration(Core.CORE_ADDRESS, "/convex/migrations/v1-metadata.cvx");
		/** convex.fungible library fixes applied as part of v1 (#528: add-mint). */
		private static final AList<ACell> FUNGIBLE_FIXES =
				readResource("/convex/migrations/v1-fungible.cvx");
		/** convex.asset library fix applied as part of v1 (#621: owns? map form). */
		private static final AList<ACell> ASSET_FIXES =
				readResource("/convex/migrations/v1-asset.cvx");
		/** asset.multi-token library fix applied as part of v1 (#620: offer clobbers holdings). */
		private static final AList<ACell> MULTITOKEN_FIXES =
				readResource("/convex/migrations/v1-multi-token.cvx");

		@Override
		public State apply(State preState) {
			// 1. Install the scheduling core bindings (CoreFn singletons, not Lisp-definable)
			AccountStatus core = preState.getAccount(Core.CORE_ADDRESS);
			AHashMap<Symbol, ACell> env = core.getEnvironment();
			env = env.assoc(Symbols.SCHEDULE_UPGRADE, Core.SCHEDULE_UPGRADE);
			env = env.assoc(Symbols.UNSCHEDULE_UPGRADE, Core.UNSCHEDULE_UPGRADE);
			env = env.assoc(Symbols.GENSYM, Core.GENSYM);
			env = env.assoc(Symbols.CAT, Core.CAT);
			env = env.assoc(Symbols.SPLICE, Core.SPLICE);

			AHashMap<Symbol, AHashMap<ACell, ACell>> meta = core.getMetadata();
			// :static only here; full metadata including :doc is applied by META_FIXES,
			// which needs these env values to exist before its no-value defs run
			AHashMap<ACell, ACell> staticMeta = Maps.of(Keywords.STATIC, CVMBool.TRUE);
			meta = meta.assoc(Symbols.SCHEDULE_UPGRADE, staticMeta);
			meta = meta.assoc(Symbols.UNSCHEDULE_UPGRADE, staticMeta);
			meta = meta.assoc(Symbols.GENSYM, staticMeta);
			meta = meta.assoc(Symbols.CAT, staticMeta);
			meta = meta.assoc(Symbols.SPLICE, staticMeta);

			State s = preState.putAccount(Core.CORE_ADDRESS, core.withEnvironment(env).withMetadata(meta));

			// 2. Apply known core function fixes (#533) and docstring corrections (#600)
			s = CORE_FIXES.apply(s);
			s = META_FIXES.apply(s);

			// 3. Apply known library fixes, resolving each library address via CNS so this
			// works on any network that has it (skipped if absent). Each redefinition is
			// idempotent: re-applying the corrected definition — e.g. after an out-of-band
			// governance patch installing the same fix — leaves the environment unchanged.
			s = applyLibraryFix(s, "convex.fungible", FUNGIBLE_FIXES);   // #528: add-mint
			s = applyLibraryFix(s, "convex.asset", ASSET_FIXES);         // #621: owns? map form
			s = applyLibraryFix(s, "asset.multi-token", MULTITOKEN_FIXES); // #620: offer clobbers holdings

			return s;
		}

		/** Applies a set of redefinition forms to the actor resolved from a CNS name, or no-op if absent. */
		private static State applyLibraryFix(State s, String cnsName, AList<ACell> forms) {
			ACell addr = s.lookupCNS(cnsName);
			if (addr instanceof Address) {
				return evalForms(s, (Address) addr, forms);
			}
			return s;
		}
	}

	/**
	 * The ordered migration list: entry {@code k} produces protocol version
	 * {@code k+1}. Append-only — never reorder, remove or edit released entries.
	 * A single fix is not its own migration: the v1 upgrade fixes all bugs known
	 * at genesis; a later upgrade is added only for bugs discovered after v1.
	 */
	private static final List<Migration> ALL = List.of(
			new Bootstrap()   // v1: mechanism bindings + all known bug fixes
	);

	/**
	 * The highest protocol version this release supports, i.e. the size of the
	 * migration list. A due upgrade beyond this version is a missing migration and
	 * causes the peer to withdraw from consensus. Advertised to other peers as
	 * upgrade readiness attestation.
	 */
	public static final long MAX_VERSION = ALL.size();

	/**
	 * Protocol version of the live Convex network (Protonet) as of this release.
	 * Bump this when the live network applies an upgrade — and only then.
	 *
	 * <p>This drives the LIVE test states (see UPGRADE.md, "Default test state
	 * policy"): while the live network trails {@link #MAX_VERSION}, releases must
	 * not break the semantics live peers are still running, so the live-side test
	 * suites (core behavioural suite, shared peer test network) are pinned to this
	 * version rather than the latest target. Never exceeds {@link #MAX_VERSION}.</p>
	 */
	public static final long LIVE_VERSION = 0;

	/**
	 * Gets the migration producing protocol version {@code k+1}.
	 *
	 * @param k Index into the migration list (current protocol version at application)
	 * @return The migration, or null if this release does not carry it
	 */
	public static Migration get(long k) {
		if ((k < 0) || (k >= MAX_VERSION)) return null;
		return ALL.get((int) k);
	}

	/**
	 * Applies every migration this release carries, in order, returning the fully
	 * upgraded State at protocol version {@link #MAX_VERSION}, with the protocol
	 * globals set to record all upgrades as applied.
	 *
	 * <p>This builds a State at the latest protocol version <em>directly</em>,
	 * without scheduling and activating each upgrade through consensus — intended
	 * for tests and tooling that need the upgraded semantics (e.g. verifying a
	 * migration's fixes). Production networks reach the same state via on-chain
	 * scheduling; genesis itself is never modified. See UPGRADE.md.</p>
	 *
	 * @param state Starting State (typically genesis at protocol version 0)
	 * @return Fully upgraded State at protocol version MAX_VERSION
	 */
	public static State applyAll(State state) {
		return applyTo(state, MAX_VERSION);
	}

	/**
	 * Applies migrations in order up to the given target protocol version,
	 * recording each as applied. Returns the State unchanged (bit-identical) if it
	 * is already at the target version — so {@code applyTo(genesis, 0)} is genesis.
	 * See {@link #applyAll(State)} for intent and caveats.
	 *
	 * @param state Starting State
	 * @param targetVersion Protocol version to migrate to (between the State's
	 *        current version and {@link #MAX_VERSION})
	 * @return State at the target protocol version
	 */
	public static State applyTo(State state, long targetVersion) {
		long current = state.getProtocolVersion();
		if (targetVersion == current) return state;
		if ((targetVersion < current) || (targetVersion > MAX_VERSION)) {
			throw new IllegalArgumentException(
					"Target protocol version " + targetVersion + " outside [" + current + "," + MAX_VERSION + "]");
		}
		long ts = state.getTimestamp().longValue();
		AVector<CVMLong> upgrades = state.getUpgradeVector();
		for (long k = current; k < targetVersion; k++) {
			state = get(k).apply(state);
			upgrades = upgrades.conj(CVMLong.create(ts)); // recorded as applied at current time
		}
		return state.withProtocolGlobals(targetVersion, upgrades);
	}

	/**
	 * Describes a scheduled network upgrade that this release cannot apply. Unless
	 * the peer software is upgraded first, the peer will withdraw from consensus at
	 * the activation timestamp. See UPGRADE.md.
	 */
	public static final class UpgradeWarning {
		/** Protocol version the scheduled upgrade will produce (exceeds MAX_VERSION). */
		public final long version;
		/** Consensus timestamp (ms) at which the upgrade activates. */
		public final long activation;

		UpgradeWarning(long version, long activation) {
			this.version = version;
			this.activation = activation;
		}
	}

	/**
	 * Detects the earliest scheduled upgrade beyond this release's supported version
	 * ({@link #MAX_VERSION}). A pure function of the consensus State: the peer will
	 * withdraw from consensus at the returned activation unless upgraded first.
	 *
	 * <p>The entry at index {@code MAX_VERSION} produces version {@code MAX_VERSION+1},
	 * which {@link #get} cannot supply; it is the earliest unsupported entry and is
	 * necessarily still pending, since a peer never advances its version beyond the
	 * versions it supports.</p>
	 *
	 * @param state Consensus State to check
	 * @return The earliest unsupported scheduled upgrade, or null if all are supported
	 */
	public static UpgradeWarning pendingBeyondSupport(State state) {
		AVector<CVMLong> upgrades = state.getUpgradeVector();
		if (MAX_VERSION < upgrades.count()) {
			long activation = upgrades.get(MAX_VERSION).longValue();
			return new UpgradeWarning(MAX_VERSION + 1, activation);
		}
		return null;
	}
}
