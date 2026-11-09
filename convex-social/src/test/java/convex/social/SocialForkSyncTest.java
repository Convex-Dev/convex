package convex.social;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;

import org.junit.jupiter.api.Test;

import convex.auth.did.DID;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.lattice.MergeConflictException;
import convex.lattice.cursor.Root;

/** Forks of feeds and follows, where both sides changed. */
class SocialForkSyncTest {

	private static final AString AUTHOR=Strings.intern("did:web:alice.example");
	private static final AString BOB=DID.forKey(AKeyPair.createSeeded(611).getAccountKey());
	private static final AString CAROL=DID.forKey(AKeyPair.createSeeded(612).getAccountKey());

	private static Feed feed(Root<Index<Blob,ACell>> root) {
		return new Feed(root,AUTHOR,() -> CVMLong.ONE);
	}

	@Test void postsOnBothSidesOfAForkAreAllPublished() {
		var root=new Root<Index<Blob,ACell>>(Index.none());
		var live=feed(root);
		var fork=live.rebind(root.fork());
		var local=fork.post("local");
		var remote=live.post("remote");
		fork.sync();
		assertNotNull(live.getPost(local));
		assertNotNull(live.getPost(remote));
	}

	@Test void aPostChangedOnBothSidesIsRefusedAndTheForkKeepsItsEdit() {
		var key=SocialPost.createKey(1,1);
		Index<Blob,ACell> base=Index.of(key,CVMLong.ONE);
		var root=new Root<Index<Blob,ACell>>(base);
		var forked=root.fork();
		var fork=feed(root).rebind(forked);
		var own=base.assoc(key,CVMLong.create(2));
		var other=base.assoc(key,CVMLong.create(3));
		forked.set(own);
		root.set(other);
		assertThrows(MergeConflictException.class,fork::sync);
		assertEquals(own,forked.get());
		assertEquals(other,root.get());
	}

	@Test void followsOfDifferentPeopleAreAllPublished() {
		var root=new Root<AHashMap<ACell,ACell>>(Maps.empty());
		var live=new Follows(root,() -> CVMLong.ONE);
		var fork=new Follows(root.fork(),() -> CVMLong.TWO);
		fork.follow(BOB);
		live.follow(CAROL);
		fork.sync();
		assertEquals(Set.of(BOB,CAROL),live.getActive());
	}

	@Test void followingTheSamePersonAtDifferentTimesIsRefused() {
		var root=new Root<AHashMap<ACell,ACell>>(Maps.empty());
		var live=new Follows(root,() -> CVMLong.ONE);
		var fork=new Follows(root.fork(),() -> CVMLong.TWO);
		fork.follow(BOB);
		live.follow(BOB);
		assertThrows(MergeConflictException.class,fork::sync);
	}
}
