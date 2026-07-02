package convex.core.cvm;

import java.util.List;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.Maps;
import convex.core.data.Symbol;
import convex.core.data.prim.CVMBool;
import convex.core.lang.Core;

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
	 * The ordered migration list: entry {@code k} produces protocol version
	 * {@code k+1}. Append-only — never reorder, remove or edit released entries.
	 */
	private static final List<Migration> ALL = List.of(
			new Bootstrap()	// v1: schedule-upgrade / unschedule-upgrade core bindings
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
}
