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
 * supports protocol versions up to {@link #supportedVersion()}; a due version beyond
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
	 * v1 bootstrap migration: installs the schedule-upgrade / unschedule-upgrade
	 * core function bindings into the core environment (account #8), marked
	 * {@code :static} like other intrinsic core definitions.
	 *
	 * <p>The protocol globals already exist when this fires: they were created by
	 * the governance transaction that scheduled this upgrade (which embedded the
	 * schedule-upgrade cell directly in compiled code, since no binding existed
	 * yet). From version 1 onward the functions resolve normally by symbol.
	 * See UPGRADE.md.</p>
	 */
	static final class Bootstrap implements Migration {
		@Override
		public State apply(State preState) {
			AccountStatus core = preState.getAccount(Core.CORE_ADDRESS);

			AHashMap<Symbol, ACell> env = core.getEnvironment();
			env = env.assoc(Symbols.SCHEDULE_UPGRADE, Core.SCHEDULE_UPGRADE);
			env = env.assoc(Symbols.UNSCHEDULE_UPGRADE, Core.UNSCHEDULE_UPGRADE);

			AHashMap<Symbol, AHashMap<ACell, ACell>> meta = core.getMetadata();
			AHashMap<ACell, ACell> staticMeta = Maps.of(Keywords.STATIC, CVMBool.TRUE);
			meta = meta.assoc(Symbols.SCHEDULE_UPGRADE, staticMeta);
			meta = meta.assoc(Symbols.UNSCHEDULE_UPGRADE, staticMeta);

			return preState.putAccount(Core.CORE_ADDRESS, core.withEnvironment(env).withMetadata(meta));
		}
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
			try {
				this.forms = Reader.readAll(Utils.readResourceAsString(resource));
			} catch (IOException e) {
				throw new ExceptionInInitializerError("Missing migration resource: " + resource);
			}
		}

		@Override
		public State apply(State preState) {
			Context ctx = Context.create(preState, account, Constants.MAX_TRANSACTION_JUICE);
			for (ACell form : forms) {
				ctx = ctx.expandCompile(form);
				if (ctx.isExceptional()) throw new IllegalStateException("Migration compile failed for " + form + ": " + ctx.getValue());
				ctx = ctx.exec((AOp<?>) ctx.getResult());
				if (ctx.isExceptional()) throw new IllegalStateException("Migration exec failed for " + form + ": " + ctx.getValue());
			}
			return ctx.getState();
		}
	}

	/**
	 * The ordered migration list: entry {@code k} produces protocol version
	 * {@code k+1}. Append-only — never reorder, remove or edit released entries.
	 */
	private static final List<Migration> ALL = List.of(
			new Bootstrap(),                                              // v1: scheduling core bindings
			new CodeMigration(Core.CORE_ADDRESS, "/convex/migrations/v2.cvx") // v2: update/update-in fixes (#533)
	);

	/**
	 * The highest protocol version this release supports, i.e. the size of the
	 * migration list. A due upgrade beyond this version is a missing migration and
	 * causes the peer to withdraw from consensus. Advertised to other peers as
	 * upgrade readiness attestation.
	 */
	public static final long MAX_VERSION = ALL.size();

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
	 * @param state Starting State (typically genesis at version 0)
	 * @return Fully upgraded State at version MAX_VERSION
	 */
	public static State applyAll(State state) {
		long ts = state.getTimestamp().longValue();
		AVector<CVMLong> upgrades = state.getUpgradeVector();
		for (long k = state.getProtocolVersion(); k < MAX_VERSION; k++) {
			state = get(k).apply(state);
			upgrades = upgrades.conj(CVMLong.create(ts)); // recorded as applied at current time
		}
		return state.withProtocolGlobals(MAX_VERSION, upgrades);
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
