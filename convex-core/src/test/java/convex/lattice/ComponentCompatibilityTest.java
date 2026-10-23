package convex.lattice;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.ASet;
import convex.core.data.AString;
import convex.core.data.AccountKey;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Sets;
import convex.core.data.SignedData;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.lattice.cursor.Root;
import convex.lattice.generic.OwnerKeyLattice;

/** Compatibility is an explicit diagnostic, independent of storage and merge acceptance. */
class ComponentCompatibilityTest {
	private static final Keyword ITEMS=Keyword.intern("items");
	private static final AString EXPECTED_INTEGER=Strings.intern("Expected an integer set member");

	@Test void baseDeclaresNoConstraints() {
		var root=Root.<ACell>create(null);
		var component=new ALatticeComponent<>(root) {};
		assertNull(component.checkValue(null));
		assertNull(component.checkValue(CVMLong.ONE));
		assertNull(component.checkValue(Maps.empty()));
		assertNull(root.get());
	}

	@Test void specialisedSetChecksForeignElementsWithoutInstallingThem() {
		var root=Root.<ASet<CVMLong>>create(null);
		// Runtime member constraints belong to the application, independently of Java generics.
		var set=new SetComponent<>(root) {
			@Override public AString checkValue(ACell value) {
				AString error=super.checkValue(value);
				if (error!=null || value==null) return error;
				for (ACell member:(ASet<?>)value) {
					if (!(member instanceof CVMLong)) return EXPECTED_INTEGER;
				}
				return null;
			}
		};
		assertNull(set.checkValue(null));
		assertNull(set.checkValue(Sets.empty()));
		assertNull(set.checkValue(Sets.of(1,2)));
		assertNotNull(set.checkValue(CVMLong.ONE));
		assertNotNull(set.checkValue(Sets.of("wrong")));
		assertNotNull(set.checkValue(Sets.<ACell>empty().include(null)));
		var ordinary=new SetComponent<>(root);
		assertNull(ordinary.checkValue(Sets.of("unconstrained")));
		assertNull(ordinary.checkValue(Sets.<ACell>empty().include(null)));
		assertNull(root.get());
	}

	@Test void localOperationsAndSyncDoNotRunCompatibilityChecks() {
		var calls=new AtomicInteger();
		var root=Root.<ASet<CVMLong>>create(null);
		var set=new SetComponent<>(root) {
			@Override public AString checkValue(ACell value) {
				calls.incrementAndGet();
				return super.checkValue(value);
			}
		};
		set.add(CVMLong.ONE);
		assertTrue(set.contains(CVMLong.ONE));
		set.remove(CVMLong.ONE);
		set.clear();
		set.sync();
		assertEquals(0,calls.get());
		assertNull(set.checkValue(Sets.of(1)));
		assertEquals(1,calls.get());
	}

	@Test void mergeAndPublicationDoNotAutomaticallyPrecheck() {
		var root=Root.<AHashMap<Keyword,ACell>>create(null);
		var component=new LatticeComponent<>(Timestamped.lattice(),root,LatticeContext.EMPTY) {
			@Override public AString checkValue(ACell value) {
				throw new AssertionError("Diagnostic must be explicitly requested");
			}
		};
		AHashMap<Keyword,ACell> record=Maps.of(Timestamped.TIMESTAMP,CVMLong.ONE);
		component.merge(record);
		component.sync();
		assertSame(record,root.get());
		// Force reconciliation as well as the uncontended publication above.
		component.cursor().set(record.assoc(Timestamped.TIMESTAMP,CVMLong.TWO));
		var newer=record.assoc(Timestamped.TIMESTAMP,CVMLong.create(3));
		root.set(newer);
		component.sync();
		assertSame(newer,root.get());
		assertSame(newer,component.cursor().get());
	}

	@Test void regionChecksComposeExistingChildrenWithoutFreezingRegistration() {
		var host=LatticeHost.create();
		var app=new Regions(host);
		var set=new SetComponent<CVMLong>(host.cursor().path(ITEMS));
		var initial=host.cursor().get();
		assertNull(app.checkValue(Index.of(ITEMS,CVMLong.ONE)),"unknown regions are outside our policy");
		app.add(ITEMS,set); // Inspection must not freeze the region set.
		assertNotNull(app.checkValue(Index.of(ITEMS,CVMLong.ONE)));
		assertTrue(app.checkValue(Index.of(ITEMS,CVMLong.ONE)).toString().contains(":items"));
		assertNull(app.checkValue(Index.of(ITEMS,Sets.of(1))));
		assertNull(app.checkValue(Index.of(ITEMS,null)));
		assertNull(app.checkValue(Index.none()));
		assertNotNull(app.checkValue(Maps.empty()));
		assertSame(initial,host.cursor().get());
	}

	@Test void timestampCheckDoesNotReadClockOrStampCandidate() {
		var root=Root.<AHashMap<Keyword,ACell>>create(null);
		var record=Timestamped.create(root,() -> { fail("check must not consult the clock"); return null; });
		assertNull(record.checkValue(null));
		assertNotNull(record.checkValue(Maps.empty()));
		assertNotNull(record.checkValue(CVMLong.ONE));
		assertNotNull(record.checkValue(Maps.of(Timestamped.TIMESTAMP,"wrong")));
		assertNull(record.checkValue(Maps.of(Timestamped.TIMESTAMP,CVMLong.ONE)));
		assertNull(root.get());
	}

	@Test void structuralCompatibilityDoesNotGrantOwnershipOrMergeAcceptance() {
		var alice=AKeyPair.createSeeded(91);
		var bob=AKeyPair.createSeeded(92);
		var destination=Root.<Index<AccountKey,SignedData<AHashMap<Keyword,ACell>>>>create(null);
		var owners=new KeyOwnedIndex<>(destination,Timestamped.lattice());
		AHashMap<Keyword,ACell> payload=Maps.of(Timestamped.TIMESTAMP,CVMLong.ONE);
		var foreign=bob.signData(payload);
		Index<AccountKey,SignedData<AHashMap<Keyword,ACell>>> candidate=Index.of(alice.getAccountKey(),foreign);
		assertNull(owners.checkValue(candidate));
		assertNotNull(owners.checkValue(Maps.empty()));
		assertNotNull(owners.checkValue(Index.of(alice.getAccountKey(),CVMLong.ONE)));
		assertNull(owners.cursor().get(),"checking must not merge or create state");
		owners.merge(candidate);
		assertNull(owners.get(alice.getAccountKey()),"merge still rejects an unauthorised signer");
		var signed=owners.draft(alice.getAccountKey(),alice::signData);
		assertNull(signed.checkValue(foreign));
		assertNotNull(signed.checkValue(payload),"signed checks take envelopes, not unsigned working values");
		signed.merge(foreign);
		assertNull(signed.cursor().get());
		assertNull(destination.get());
	}

	@Test void signedRegionChecksUseTheDeliveryRepresentation() {
		var key=AKeyPair.createSeeded(93);
		var host=LatticeHost.create();
		var rule=OwnerKeyLattice.create(Timestamped.lattice()).signingFor(key.getAccountKey());
		var signed=new SignedComponent<>(rule,host.cursor().path(ITEMS),key::signData);
		var app=new Regions(host);
		app.add(ITEMS,signed);
		AHashMap<Keyword,ACell> payload=Maps.of(Timestamped.TIMESTAMP,CVMLong.ONE);
		assertNull(app.checkValue(Index.of(ITEMS,key.signData(payload))));
		assertNotNull(app.checkValue(Index.of(ITEMS,payload)));
		assertNull(signed.cursor().get());
	}

	@Test void adaptersCheckRawCandidatesWithoutChangingTheirMergeBehaviour() {
		var host=LatticeHost.<AHashMap<Keyword,ACell>>create(null,null);
		var app=new LatticeApplication<>(Timestamped.lattice(),host);
		var component=LatticeComponent.live(Timestamped.lattice(),Root.create(null),LatticeContext.EMPTY);
		for (ALatticeComponent<?> adapter:new ALatticeComponent<?>[] {app,component}) {
			assertNull(adapter.checkValue(null));
			assertNotNull(adapter.checkValue(Strings.create("wrong")));
			assertNotNull(adapter.checkValue(Maps.empty()));
			assertNull(adapter.checkValue(Maps.of(Timestamped.TIMESTAMP,CVMLong.ONE)));
		}
	}
}

