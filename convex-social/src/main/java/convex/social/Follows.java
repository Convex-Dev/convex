package convex.social;

import java.util.Set;
import java.util.function.Supplier;

import convex.auth.did.DID;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.AccountKey;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.lattice.ALatticeComponent;
import convex.lattice.MapComponent;
import convex.lattice.TransactionalComponent;
import convex.lattice.cursor.AForkableCursor;

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
public class Follows extends ALatticeComponent<AHashMap<ACell, ACell>> implements TransactionalComponent<AHashMap<ACell, ACell>,Follows> {
	private final Supplier<CVMLong> clock;
	private final MapComponent<ACell,ACell> records;

	Follows(AForkableCursor<AHashMap<ACell, ACell>> cursor, Supplier<CVMLong> clock) {
		super(cursor);
		this.clock=clock;
		this.records=new MapComponent<>(cursor);
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
	 * @return True if actively followed; false if the record is absent
	 */
	public boolean isFollowing(AString target) {
		return SocialPost.isActiveFollow(getRecord(target));
	}

	/**
	 * Gets the set of actively followed DIDs.
	 *
	 * @return Set of canonical base DIDs, or null if the follows map is absent
	 */
	public Set<AString> getActive() {
		return SocialHelpers.getActiveFollows(cursor().get());
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

	/** Returns the stored follow record, or null if absent. Incompatible values throw. */
	@SuppressWarnings("unchecked")
	public AHashMap<Keyword,ACell> getRecord(AString target) {
		AHashMap<ACell,ACell> follows=cursor().get();
		return follows==null?null:(AHashMap<Keyword,ACell>)follows.get(target);
	}

	@SuppressWarnings("unchecked")
	private void writeField(AString target,Keyword key,ACell value) {
		CVMLong timestamp=clock.get();
		records.path(target).updateAndGet(existing -> {
			AHashMap<Keyword,ACell> record=(AHashMap<Keyword,ACell>)existing;
			if (record==null) record=Maps.empty();
			return record.assoc(key,value).assoc(SocialPost.TIMESTAMP,timestamp);
		});
	}

	@Override
	public Follows withCursor(AForkableCursor<AHashMap<ACell, ACell>> cursor) {
		return new Follows(cursor,clock);
	}

}
