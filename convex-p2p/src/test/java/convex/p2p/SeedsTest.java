package convex.p2p;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.util.List;

import org.junit.jupiter.api.Test;

import convex.auth.did.DIDKeyAuthorizer;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AccountKey;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.store.MemoryStore;
import convex.lattice.LatticeHost;
import convex.lattice.LocalLattice;
import convex.lattice.MapComponent;
import convex.net.PeerName;

/** A node's seed list is local state: one entry per peer URI, bounded, kept by the host. */
public class SeedsTest {

	private static final AKeyPair ALICE=AKeyPair.createSeeded(9401);
	private static final AccountKey HUB=AKeyPair.createSeeded(9402).getAccountKey();
	private static final AccountKey OTHER=AKeyPair.createSeeded(9403).getAccountKey();

	private static P2PApplication alice(LatticeHost<Index<Keyword,ACell>> host) {
		return host.addApplication(P2PApplication.connect(P2PLattice.ROOT,host,ALICE.getAccountKey(),
			(owner,value) -> ALICE.signData(value),() -> CVMLong.create(100),DIDKeyAuthorizer.CONVEX));
	}

	private static PeerName name(String uri,AccountKey key) {
		return new PeerName(URI.create(uri),key);
	}

	@Test void aSeedIsListedWithTheKeyItMustProve() {
		Seeds seeds=alice(LatticeHost.create()).seeds();
		PeerName hub=name("tcp://hub.example:18888",HUB);
		PeerName web=name("did:web:seed.example",null);
		assertTrue(seeds.add(hub));
		assertTrue(seeds.add(web));
		assertEquals(List.of(web,hub),seeds.all());
		assertTrue(seeds.contains(hub));
		assertFalse(seeds.contains(name("tcp://hub.example:18888",OTHER)),"listed with another key");
		assertFalse(seeds.contains(name("tcp://hub.example:18888",null)));
	}

	@Test void eachURIIsOneSeed() {
		Seeds seeds=alice(LatticeHost.create()).seeds();
		assertTrue(seeds.add(name("tcp://hub.example:18888",HUB)));
		assertFalse(seeds.add(name("tcp://hub.example:18888",OTHER)),"the same URI replaces the entry");
		assertEquals(List.of(name("tcp://hub.example:18888",OTHER)),seeds.all());
		assertTrue(seeds.add(name("did:key:z6MkhaXgBZDvotDkL5257faiztiGiC2QtKLGpbnnEGta2doK",null)),
			"another name for the same node is another seed");
		assertEquals(2,seeds.all().size());

		assertTrue(seeds.remove(name("tcp://hub.example:18888",null)),"removed by URI, whatever the key");
		assertFalse(seeds.remove(name("tcp://hub.example:18888",null)));
		assertEquals(1,seeds.all().size());
	}

	@Test void atMostMaxSeedsAreListed() {
		Seeds seeds=alice(LatticeHost.create()).seeds();
		for (int i=0; i<Seeds.MAX_SEEDS; i++) seeds.add(name("tcp://seed"+i+".example:18888",null));
		PeerName extra=name("tcp://extra.example:18888",null);
		assertThrows(IllegalStateException.class,() -> seeds.add(extra));
		assertFalse(seeds.add(name("tcp://seed0.example:18888",HUB)),"a listed seed may still change");
		assertEquals(Seeds.MAX_SEEDS,seeds.all().size());
	}

	@Test void anEntryThatIsNotAPeerNameIsSkipped() {
		P2PApplication alice=alice(LatticeHost.create());
		var stored=new MapComponent<Keyword,ACell>(alice.contacts().cursor()).<Index<AString,ACell>>path(Seeds.KEY);
		PeerName hub=name("tcp://hub.example:18888",HUB);
		stored.set(Index.<AString,ACell>of(Strings.create("not a uri"),Maps.empty(),
			Strings.create("tcp://bad-key.example:18888"),Maps.of(Seeds.EXPECTED_KEY,CVMLong.create(5)),
			Strings.create(hub.uri().toString()),Maps.of(Seeds.EXPECTED_KEY,HUB)));
		assertEquals(List.of(hub),alice.seeds().all());
	}

	@Test void additionsAndRemovalsSurviveARestart() throws Exception {
		var store=new MemoryStore();
		var host=LatticeHost.create(store);
		var application=alice(host);
		PeerName hub=name("tcp://hub.example:18888",HUB);
		PeerName gone=name("tcp://gone.example:18888",null);
		application.seeds().add(hub);
		application.seeds().add(gone);
		application.syncHost();
		application.seeds().remove(gone);
		application.syncHost();

		assertEquals(List.of(hub),alice(LatticeHost.open(store)).seeds().all());
	}

	@Test void aNodeWithoutAKeyKeepsItsSeedsInMemory() {
		var host=LatticeHost.<Index<Keyword,ACell>>create();
		var relay=host.addApplication(P2PApplication.connect(P2PLattice.ROOT,host));
		PeerName hub=name("tcp://hub.example:18888",HUB);
		assertTrue(relay.seeds().add(hub));
		relay.syncHost();
		assertEquals(List.of(hub),relay.seeds().all());
		assertNull(host.cursor().get(LocalLattice.KEY_LOCAL));
	}
}
