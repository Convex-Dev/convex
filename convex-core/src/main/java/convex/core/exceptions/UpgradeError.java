package convex.core.exceptions;

/**
 * Error thrown when a due network upgrade cannot be applied by this peer, either
 * because this release lacks the required migration or because the migration failed.
 *
 * <p>Deliberately an {@link Error}: it must <b>not</b> be converted into an
 * invalid-block result by the transition function's exception handling.
 * Invalid-block outcomes are consensus history, so a later release carrying a
 * corrected migration would recompute those blocks differently on replay,
 * splitting replay from the live network. Instead this propagates to the peer
 * layer, which withdraws from consensus participation until running a release
 * that can apply the upgrade. See UPGRADE.md.</p>
 *
 * <p>The cause distinguishes failure classes for operator diagnostics: no cause
 * means a missing migration (release update required); a deterministic migration
 * bug carries the underlying exception (corrected release required); a peer-local
 * condition such as {@link MissingDataException} means resync-and-retry may
 * succeed without any release change. All classes resolve to the same safe
 * behaviour: produce no state, withdraw.</p>
 */
@SuppressWarnings("serial")
public class UpgradeError extends Error {

	private final long version;

	private UpgradeError(String message, Throwable cause, long version) {
		super(message, cause);
		this.version = version;
	}

	/**
	 * Creates an UpgradeError for a migration missing from this release.
	 * @param version Protocol version whose migration is unavailable
	 * @return New UpgradeError
	 */
	public static UpgradeError missing(long version) {
		return new UpgradeError("Missing migration for protocol version " + version
				+ ": peer release update required", null, version);
	}

	/**
	 * Creates an UpgradeError for a migration that threw during application.
	 * @param version Protocol version whose migration failed
	 * @param cause Underlying failure
	 * @return New UpgradeError
	 */
	public static UpgradeError failed(long version, Throwable cause) {
		return new UpgradeError("Migration for protocol version " + version + " failed", cause, version);
	}

	/**
	 * Gets the protocol version whose migration could not be applied
	 * @return Protocol version
	 */
	public long getVersion() {
		return version;
	}

	/**
	 * Is this a retryable, peer-local (environmental) failure rather than a
	 * deterministic one?
	 *
	 * <p>A deterministic failure (missing migration, or a migration bug that throws
	 * identically everywhere) requires a corrected release: retrying the same
	 * release recomputes the same failure. A peer-local failure such as
	 * {@link MissingDataException} — an incomplete store — may succeed on
	 * resync-and-retry with no release change, and must not be treated as a
	 * permanent freeze. See UPGRADE.md.</p>
	 *
	 * @return true if the peer may retry without a release change
	 */
	public boolean isRetryable() {
		return getCause() instanceof MissingDataException;
	}
}
