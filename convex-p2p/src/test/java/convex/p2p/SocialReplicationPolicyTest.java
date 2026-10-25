package convex.p2p;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import convex.auth.did.DID;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.lattice.LatticeHost;
import convex.social.Social;

class SocialReplicationPolicyTest {

	@Test void receivingAndReplayingFollowedOwnerLeavesLocalIntentUnchanged() {
		var alice=AKeyPair.createSeeded(7812);
		var bob=AKeyPair.createSeeded(7813);
		var aliceDid=DID.forKey(alice.getAccountKey());
		var bobDid=DID.forKey(bob.getAccountKey());
		var host=LatticeHost.create();
		var application=host.addApplication(P2PApplication.connect(P2PLattice.NODE_ROOT,host));
		var local=application.social(alice);
		local.user(aliceDid).follows().follow(bobDid);
		var original=local.cursor().get().get(aliceDid);
		var publications=new AtomicInteger();
		host.attachPublisher(value -> { publications.incrementAndGet(); return value; });

		var remote=Social.create(bob);
		remote.user(bobDid).feed().post("Hello");
		var envelope=remote.cursor().get().get(bobDid);
		Index<Keyword,ACell> incoming=Index.of(Social.KEY_SOCIAL,Maps.of(bobDid,envelope));
		for (int delivery=0;delivery<2;delivery++) {
			application.merge(incoming);
			assertSame(envelope,local.cursor().get().get(bobDid));
			// Receiving another owner's data must not re-sign or re-stamp our intent.
			assertSame(original,local.cursor().get().get(aliceDid));
			assertEquals(0,publications.get(),"Merge must not publish a local bookkeeping edit");
		}
	}
}
