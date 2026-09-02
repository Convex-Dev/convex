package convex.social;

import java.util.Set;

import convex.auth.did.DID;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.AccountKey;
import convex.core.data.Keyword;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.lattice.ALatticeComponent;
import convex.lattice.cursor.ALatticeCursor;

/**
 * Cursor wrapper for a user's follow list.
 *
 * <p>A {@code Follows} wraps a lattice cursor at the {@code :following :follows}
 * level ({@code AHashMap<ACell, ACell>} with LWW per DID). The {@code :active}
 * flag enables follow/unfollow toggling — the latest timestamped record
 * for each target DID wins.</p>
 *
 * <pre>{@code
 * Follows follows = social.user(myDid).follows();
 * follows.follow(bobDid);
 * follows.unfollow(bobDid);
 * Set<AString> active = follows.getActive();
 * }</pre>
 */
public class Follows extends ALatticeComponent<AHashMap<ACell, ACell>> {
	public static final Keyword KEY_ACCOUNT_KEY=Keyword.intern("account-key");

	Follows(SocialUser parent, ALatticeCursor<AHashMap<ACell, ACell>> cursor) {
		super(parent,cursor);
	}

	/**
	 * Follows a user.
	 *
	 * @param target canonical base DID of the user to follow
	 */
	public void follow(AString target) {
		requireDID(target);
		writeField(target,SocialPost.ACTIVE,CVMBool.TRUE);
	}

	/**
	 * Unfollows a user.
	 *
	 * @param target canonical base DID of the user to unfollow
	 */
	public void unfollow(AString target) {
		requireDID(target);
		writeField(target,SocialPost.ACTIVE,CVMBool.FALSE);
	}

	/**
	 * Checks if a user is actively followed.
	 *
	 * @param target canonical base DID to check
	 * @return true if the user is actively followed
	 */
	@SuppressWarnings("unchecked")
	public boolean isFollowing(AString target) {
		AHashMap<ACell, ACell> follows = cursor.get();
		if (follows == null) return false;
		ACell record = follows.get(target);
		if (record instanceof AHashMap<?,?> map) {
			return SocialPost.isActiveFollow((AHashMap<Keyword, ACell>) map);
		}
		return false;
	}

	/**
	 * Gets the set of actively followed DIDs.
	 *
	 * @return set of canonical base DIDs
	 */
	public Set<AString> getActive() {
		return SocialHelpers.getActiveFollows(cursor.get());
	}

	/** Stores a signer already validated for this target DID without changing intent. */
	public void cacheValidatedKey(AString target,AccountKey accountKey) {
		requireDID(target);
		if (accountKey==null) throw new IllegalArgumentException("Account key must not be null");
		if (getRecord(target)==null) throw new IllegalStateException(
			"Cannot cache a key before a follow record exists");
		writeField(target,KEY_ACCOUNT_KEY,accountKey);
	}

	/** Gets the last validated signer cached in the winning target record. */
	@SuppressWarnings("unchecked")
	public AccountKey getCachedAccountKey(AString target) {
		AHashMap<Keyword,ACell> record=getRecord(target);
		return (record==null)?null:AccountKey.parse(record.get(KEY_ACCOUNT_KEY));
	}

	/** Convenience migration overload mapping an Ed25519 key to {@code did:key}. */
	public void follow(AccountKey target) { follow(DID.forKey(target)); }
	public void unfollow(AccountKey target) { unfollow(DID.forKey(target)); }
	public boolean isFollowing(AccountKey target) { return isFollowing(DID.forKey(target)); }

	private static void requireDID(AString target) {
		if (!DID.isCanonicalBase(target)) {
			throw new IllegalArgumentException("Follow target must be a canonical base DID");
		}
	}

	@SuppressWarnings("unchecked")
	private AHashMap<Keyword,ACell> getRecord(AString target) {
		AHashMap<ACell,ACell> follows=cursor.get();
		if (follows==null) return null;
		ACell record=follows.get(target);
		return (record instanceof AHashMap<?,?> map)
			?(AHashMap<Keyword,ACell>)map:null;
	}

	@SuppressWarnings("unchecked")
	private void writeField(AString target,Keyword key,ACell value) {
		CVMLong timestamp=cursor.getContext().currentTimestamp();
		cursor.updateAndGet(follows -> {
			ACell existing=(follows==null)?null:follows.get(target);
			AHashMap<Keyword,ACell> record=(existing instanceof AHashMap<?,?> map)
				?(AHashMap<Keyword,ACell>)map:convex.core.data.Maps.empty();
			record=record.assoc(key,value)
				.assoc(SocialPost.TIMESTAMP,timestamp);
			AHashMap<ACell,ACell> base=(follows==null)
				?convex.core.data.Maps.empty():follows;
			return base.assoc(target,record);
		});
	}

}
