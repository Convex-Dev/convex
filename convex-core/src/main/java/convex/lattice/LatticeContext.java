package convex.lattice;

import java.util.function.BiPredicate;

import convex.core.crypto.AKeyPair;
import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AccountKey;
import convex.core.data.SignedData;
import convex.core.data.prim.CVMLong;
import convex.core.util.Utils;

/**
 * Application policy available to lattice merge and write operations.
 *
 * <p>A context is normally installed once on an application or root cursor and
 * inherited by descendants. Implementations may return fixed values (useful for
 * deterministic tests) or resolve policies dynamically, for example from an
 * application clock, wallet or key store. Dynamic implementations used by shared
 * cursors must be thread-safe.</p>
 *
 * <p>The small {@code with...} methods return delegated policy overrides: each
 * replaces one capability outright and delegates every other to this context, so a
 * fixed timestamp does not freeze a dynamic signing policy, or vice versa.</p>
 *
 * <p>{@link #signAs} is the single rule for authoring owned data, and it is the rule
 * {@link #verifyOwner} applies to data arriving on merge. Authoring and adopting are
 * therefore judged identically, so a value is never written locally in a form a peer
 * would reject.</p>
 */
public abstract class LatticeContext {

	/** Default policy: runtime time, no signer, lenient non-key owner verification. */
	public static final LatticeContext EMPTY = new LatticeContext() { };

	/** Constructor for application-defined policy implementations. */
	protected LatticeContext() {
	}

	/** Creates a fixed timestamp/key policy. A null timestamp uses runtime time. */
	public static LatticeContext create(CVMLong timestamp, AKeyPair signingKey) {
		return create(timestamp,signingKey,null,null);
	}

	/** Creates a fixed timestamp/key/verifier policy. */
	public static LatticeContext create(CVMLong timestamp, AKeyPair signingKey,
			BiPredicate<ACell, AccountKey> ownerVerifier) {
		return create(timestamp,signingKey,ownerVerifier,null);
	}

	private static LatticeContext create(CVMLong timestamp, AKeyPair signingKey,
			BiPredicate<ACell, AccountKey> ownerVerifier, Long maxFutureTimestampSkew) {
		if (timestamp==null && signingKey==null && ownerVerifier==null
				&& maxFutureTimestampSkew==null) return EMPTY;
		return new Fixed(timestamp,signingKey,ownerVerifier,maxFutureTimestampSkew);
	}

	/** Returns a delegated policy with an exact timestamp override. */
	public LatticeContext withTimestamp(CVMLong timestamp) {
		return new TimestampOverride(this,timestamp);
	}

	/**
	 * Returns a delegated policy whose signing capability is this single key pair,
	 * replacing any signer offered by this context. A null key clears signing.
	 */
	public LatticeContext withSigningKey(AKeyPair signingKey) {
		return new SigningOverride(this,signingKey);
	}

	/** Returns a delegated policy with an owner-verifier override. */
	public LatticeContext withOwnerVerifier(
			BiPredicate<ACell, AccountKey> ownerVerifier) {
		return new OwnerVerifierOverride(this,ownerVerifier);
	}

	/** Returns a delegated policy with a future-timestamp-skew override. */
	public LatticeContext withMaxFutureTimestampSkew(long skewMillis) {
		if (skewMillis<0) {
			throw new IllegalArgumentException("Future timestamp skew must not be negative");
		}
		return new FutureSkewOverride(this,skewMillis);
	}

	/**
	 * Gets a fixed timestamp exposed by this policy, or null when time is resolved
	 * dynamically. This is primarily an introspection method; writes should call
	 * {@link #currentTimestamp()}.
	 */
	public CVMLong getTimestamp() {
		return null;
	}

	/**
	 * Resolves the application write/merge time. The default dynamic policy uses
	 * the canonical runtime timestamp. Implementations must not derive a later
	 * value from lattice state.
	 */
	public CVMLong currentTimestamp() {
		CVMLong timestamp=getTimestamp();
		return (timestamp!=null)?timestamp:Utils.getCurrentTimestampCell();
	}

	/** Returns the resolved timestamp as a primitive long. */
	public long currentTimestampValue() {
		return currentTimestamp().longValue();
	}

	/**
	 * Signs a value using an optional requested account key.
	 *
	 * <p>A null account requests the policy's primary signer. A non-null account
	 * allows a context backed by a wallet, key store or remote signer to select a
	 * non-primary accessible key.</p>
	 *
	 * @param <T> value type
	 * @param accountKey requested signing account, or null for the primary signer
	 * @param value value to sign
	 * @return signed value, or null when the requested signer is unavailable
	 */
	public <T extends ACell> SignedData<T> sign(AccountKey accountKey, T value) {
		return signWith(getSigningKey(),accountKey,value);
	}

	/**
	 * Signs with a single key pair, or returns null when it is absent or is not the
	 * requested account. This is the behaviour of a policy holding one key.
	 */
	protected static <T extends ACell> SignedData<T> signWith(AKeyPair keyPair,
			AccountKey accountKey, T value) {
		if (keyPair==null) return null;
		if (accountKey!=null && !accountKey.equals(keyPair.getAccountKey())) return null;
		return keyPair.signData(value);
	}

	/** Signs with the primary signer. */
	public final <T extends ACell> SignedData<T> sign(T value) {
		return sign(null,value);
	}

	/**
	 * Signs a value on behalf of an owner identity, or returns null when this policy
	 * has no signer authorised for that owner.
	 *
	 * <p>This is the single authorisation rule for <em>authoring</em> owned data, and
	 * it is exactly the rule {@link #verifyOwner} applies to data arriving on merge.
	 * An owner which directly denotes an account key requires that key; an indirect
	 * owner (Address, DID, ...) is resolved by the owner verifier, so a policy with no
	 * verifier stays lenient. Callers therefore never author a slot locally in a form
	 * that a peer would reject.</p>
	 *
	 * @param <T> value type
	 * @param owner Owner identity, or null to sign with the primary signer
	 * @param value Value to sign
	 * @return Signed value, or null when no authorised signer is available
	 */
	public final <T extends ACell> SignedData<T> signAs(ACell owner, T value) {
		SignedData<T> signed=sign(ownerAccountKey(owner),value);
		if (signed==null) return null;
		if (owner!=null && !verifyOwner(owner,signed.getAccountKey())) return null;
		return signed;
	}

	/**
	 * Returns the primary in-memory key pair when exposed by this policy.
	 * Applications with external signing services may return null and override
	 * {@link #sign(AccountKey, ACell)} directly.
	 */
	public AKeyPair getSigningKey() {
		return null;
	}

	/** Gets an application-defined owner verifier, or null for lenient mode. */
	public BiPredicate<ACell, AccountKey> getOwnerVerifier() {
		return null;
	}

	/** Verifies that a signer is authorised for an owner. */
	public boolean verifyOwner(ACell ownerKey, AccountKey signerKey) {
		return verifyOwner(ownerKey,signerKey,getOwnerVerifier());
	}

	private static boolean verifyOwner(ACell ownerKey, AccountKey signerKey,
			BiPredicate<ACell, AccountKey> ownerVerifier) {
		AccountKey direct=ownerAccountKey(ownerKey);
		if (direct!=null) return direct.equals(signerKey);
		return (ownerVerifier==null)||ownerVerifier.test(ownerKey,signerKey);
	}

	/**
	 * Gets the account key that an owner identity denotes directly, or null when the
	 * owner is an indirect identity (Address, DID string, ...) whose authorised
	 * signers are resolved by an owner verifier instead.
	 *
	 * @param ownerKey Owner identity, or null
	 * @return Account key denoted by the owner, or null
	 */
	public static AccountKey ownerAccountKey(ACell ownerKey) {
		if (ownerKey instanceof AccountKey ak) return ak;
		if (ownerKey instanceof ABlob blob && blob.count()==AccountKey.LENGTH) {
			return AccountKey.create(blob);
		}
		return null;
	}

	/**
	 * Gets the accepted future timestamp skew, or the lattice's own default when this
	 * policy does not override it. The allowance itself is validated where it is
	 * configured, in {@link #withMaxFutureTimestampSkew(long)}.
	 *
	 * @param defaultValue Lattice-specific default in milliseconds
	 * @return Future skew allowance in milliseconds
	 */
	public long getMaxFutureTimestampSkew(long defaultValue) {
		return defaultValue;
	}

	/** Immutable fixed policy used by compatibility factories. */
	private static final class Fixed extends LatticeContext {
		private final CVMLong timestamp;
		private final AKeyPair signingKey;
		private final BiPredicate<ACell,AccountKey> ownerVerifier;
		private final Long maxFutureTimestampSkew;

		private Fixed(CVMLong timestamp, AKeyPair signingKey,
				BiPredicate<ACell, AccountKey> ownerVerifier,
				Long maxFutureTimestampSkew) {
			this.timestamp=timestamp;
			this.signingKey=signingKey;
			this.ownerVerifier=ownerVerifier;
			this.maxFutureTimestampSkew=maxFutureTimestampSkew;
		}

		@Override public CVMLong getTimestamp() { return timestamp; }
		@Override public AKeyPair getSigningKey() { return signingKey; }
		@Override public BiPredicate<ACell, AccountKey> getOwnerVerifier() { return ownerVerifier; }
		@Override public long getMaxFutureTimestampSkew(long defaultValue) {
			return (maxFutureTimestampSkew==null)?defaultValue:maxFutureTimestampSkew;
		}
	}

	/** Base for small overrides which retain all other live policy. */
	private static class Delegating extends LatticeContext {
		protected final LatticeContext delegate;

		private Delegating(LatticeContext delegate) {
			this.delegate=(delegate==null)?EMPTY:delegate;
		}

		@Override public CVMLong getTimestamp() { return delegate.getTimestamp(); }
		@Override public CVMLong currentTimestamp() { return delegate.currentTimestamp(); }
		@Override public <T extends ACell> SignedData<T> sign(AccountKey accountKey, T value) {
			return delegate.sign(accountKey,value);
		}
		@Override public AKeyPair getSigningKey() { return delegate.getSigningKey(); }
		@Override public BiPredicate<ACell, AccountKey> getOwnerVerifier() {
			return delegate.getOwnerVerifier();
		}
		@Override public boolean verifyOwner(ACell ownerKey, AccountKey signerKey) {
			return delegate.verifyOwner(ownerKey,signerKey);
		}
		@Override public long getMaxFutureTimestampSkew(long defaultValue) {
			return delegate.getMaxFutureTimestampSkew(defaultValue);
		}
	}

	private static final class TimestampOverride extends Delegating {
		private final CVMLong timestamp;

		private TimestampOverride(LatticeContext delegate, CVMLong timestamp) {
			super(delegate);
			this.timestamp=timestamp;
		}

		@Override public CVMLong getTimestamp() { return timestamp; }
		@Override public CVMLong currentTimestamp() {
			// A cleared override restores the delegate's clock, which may itself be dynamic
			return (timestamp==null)?delegate.currentTimestamp():timestamp;
		}
	}

	private static final class SigningOverride extends Delegating {
		private final AKeyPair signingKey;

		private SigningOverride(LatticeContext delegate, AKeyPair signingKey) {
			super(delegate);
			this.signingKey=signingKey;
		}

		@Override public AKeyPair getSigningKey() { return signingKey; }
		@Override public <T extends ACell> SignedData<T> sign(AccountKey accountKey, T value) {
			// Replaces the delegate's signing capability outright: a policy that reports
			// no signer must not still sign, and a null override clears signing entirely
			return signWith(signingKey,accountKey,value);
		}
	}

	private static final class OwnerVerifierOverride extends Delegating {
		private final BiPredicate<ACell,AccountKey> ownerVerifier;

		private OwnerVerifierOverride(LatticeContext delegate,
				BiPredicate<ACell, AccountKey> ownerVerifier) {
			super(delegate);
			this.ownerVerifier=ownerVerifier;
		}

		@Override public BiPredicate<ACell, AccountKey> getOwnerVerifier() {
			return ownerVerifier;
		}
		@Override public boolean verifyOwner(ACell ownerKey, AccountKey signerKey) {
			return LatticeContext.verifyOwner(ownerKey,signerKey,ownerVerifier);
		}
	}

	private static final class FutureSkewOverride extends Delegating {
		private final long maxFutureTimestampSkew;

		private FutureSkewOverride(LatticeContext delegate, long maxFutureTimestampSkew) {
			super(delegate);
			this.maxFutureTimestampSkew=maxFutureTimestampSkew;
		}

		@Override public long getMaxFutureTimestampSkew(long defaultValue) {
			return maxFutureTimestampSkew;
		}
	}
}
