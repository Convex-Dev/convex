package convex.lattice.kv;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import convex.auth.did.DID;
import convex.core.crypto.AKeyPair;
import convex.core.cvm.Address;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Index;
import convex.core.data.Maps;
import convex.core.data.SignedData;
import convex.core.data.Strings;
import convex.lattice.LatticeContext;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.Cursors;
import convex.lattice.cursor.Root;
import convex.lattice.generic.MapLattice;

/**
 * Tests for KVDatabase — signed, replicated KV stores in the global lattice.
 */
public class KVDatabaseTest {

	@Test void namedDatabaseAccessRejectsIncompatibleParentsAndPreservesSiblings() {
		for (boolean explicitReplica:new boolean[] {false,true}) {
			var root=new Root<ACell>();
			var db=explicitReplica?KVDatabase.connect(root,"db",Strings.intern("replica")):KVDatabase.connect(root,"db");
			for (ACell bad:new ACell[] {Strings.intern("not a map"),Index.none()}) {
				root.set(bad);
				assertThrows(IllegalStateException.class,() -> db.kv().get("key"));
				assertThrows(IllegalStateException.class,() -> db.kv().set("key",Strings.intern("value")));
				assertSame(bad,root.get());
			}
			var opaque=Strings.intern("future database");
			root.set(Maps.of(Strings.intern("other"),opaque));
			db.kv().set("key",Strings.intern("value"));
			assertSame(opaque,((AHashMap<?,?>)root.get()).get(Strings.intern("other")));
		}
	}

	@Test
	public void testDefaultReplicaRuleRejectsUnresolvedOwners() {
		var attacker=AKeyPair.createSeeded(9291);
		var recipient=AKeyPair.createSeeded(9292);
		var source=KVDatabase.create("db",attacker);
		source.kv().set("injected",Strings.create("unrelated signer"));
		for (ACell claimed:new ACell[]{Strings.create("did:web:victim.example"),
				Strings.create("did:convex:1337"),Address.create(1337)}) {
			var local=KVDatabase.create("db",recipient);
			var remote=source.exportReplica().dissoc(attacker.getAccountKey())
				.assoc(claimed,source.getSignedState());
			var before=local.kv().cursor().get();
			assertEquals(0,local.mergeReplicas(remote),"unknown owners must fail closed");
			assertSame(before,local.kv().cursor().get());
			var unauthorised=KVDatabase.create("db",attacker,claimed,Strings.create("replica"));
			assertThrows(IllegalStateException.class,unauthorised::exportReplica);
		}
	}

	@Test
	public void testDefaultReplicaRuleAcceptsSelfCertifyingDID() {
		var key=AKeyPair.createSeeded(9293);
		var did=DID.forKey(key.getAccountKey());
		var source=KVDatabase.create("db",key,did,Strings.create("source"));
		source.kv().set("key",Strings.create("value"));
		var local=KVDatabase.create("db",AKeyPair.createSeeded(9294));
		assertEquals(1,local.mergeReplicas(source.exportReplica()));
		assertEquals(Strings.create("value"),local.kv().get("key"));
		var wrongKey=KVDatabase.create("db",AKeyPair.createSeeded(9295),did,Strings.create("wrong"));
		assertThrows(IllegalStateException.class,wrongKey::exportReplica);
	}

	@Test
	public void testExplicitOwnerRuleAppliesToMergeExportAndRotation() {
		var localKey=AKeyPair.createSeeded(9296);
		var original=AKeyPair.createSeeded(9297);
		var rotated=AKeyPair.createSeeded(9298);
		var did=Strings.create("did:web:owner.example");
		var authority=new java.util.concurrent.atomic.AtomicReference<>(original.getAccountKey());
		java.util.function.BiPredicate<ACell,convex.core.data.AccountKey> verifier=(owner,key)->
			owner.equals(localKey.getAccountKey())?key.equals(localKey.getAccountKey()):
				did.equals(owner)&&key.equals(authority.get());
		var local=KVDatabase.create("db",localKey,localKey.getAccountKey(),Strings.create("local"),verifier);
		var source=KVDatabase.create("db",original,did,Strings.create("remote"),verifier);
		source.kv().set("first",Strings.create("authorised"));
		var originalExport=source.exportReplica();
		assertEquals(1,local.mergeReplicas(originalExport));
		assertTrue(local.exportReplica().get(localKey.getAccountKey()).checkSignature());

		var attacker=KVDatabase.create("db",rotated);
		attacker.kv().set("injected",Strings.create("unauthorised"));
		var forged=attacker.exportReplica().dissoc(rotated.getAccountKey()).assoc(did,attacker.getSignedState());
		assertEquals(0,local.mergeReplicas(forged));
		assertNull(local.kv().get("injected"));

		authority.set(rotated.getAccountKey());
		assertEquals(0,local.mergeReplicas(originalExport),"revocation must affect subsequent delivery");
		assertThrows(IllegalStateException.class,source::exportReplica);
		var replacement=KVDatabase.create("db",rotated,did,Strings.create("rotated"),verifier);
		replacement.kv().set("next",Strings.create("new key"));
		assertEquals(1,local.mergeReplicas(replacement.exportReplica()));
		assertEquals(Strings.create("new key"),local.kv().get("next"));
		assertEquals(Strings.create("authorised"),local.kv().get("first"),"revocation does not erase accepted state");
	}

	@Test
	public void testConnectingAbsentDatabaseDoesNotWrite() {
		for (boolean explicitReplica:new boolean[]{false,true}) {
			var parent=Root.<AHashMap<AString,ACell>>create(null);
			var db=explicitReplica?KVDatabase.connect(parent,"db",Strings.create("replica")):
				KVDatabase.connect(parent,"db");
			assertNull(parent.get(),"connecting must not initialise or sign state");
			assertNull(db.kv().get("missing"));
			assertFalse(db.kv().del("missing"));
			db.kv().sync();
			assertNull(parent.get(),"reads and no-op edits must preserve absence");
			db.kv().set("first",Strings.create("value"));
			assertEquals(Strings.create("value"),db.kv().get("first"));
			assertNotNull(parent.get().get(Strings.create("db")));
		}
	}

	@Test
	public void testConnectPreservesConcurrentCreation() {
		var content=LatticeKV.create();
		content.set("valuable",Strings.create("concurrent write"));
		AHashMap<AString,ACell> populated=
			Maps.of(Strings.create("db"),content.cursor().get());
		for (boolean explicitReplica:new boolean[]{false,true}) {
			// Deterministically publish another writer's database just after a read of
			// absent state. A read-then-set initialisation would erase that writer.
			var parent=new Root<AHashMap<AString,ACell>>(Maps.empty()) {
				boolean pending=true;
				void publish() {
					if (!pending) return;
					pending=false;
					super.set(populated);
				}
				@Override public AHashMap<AString,ACell> get() {
					var observed=super.get();
					publish();
					return observed;
				}
			};
			var db=explicitReplica?KVDatabase.connect(parent,"db",Strings.create("replica")):
				KVDatabase.connect(parent,"db");
			parent.publish(); // A read-free connect leaves the other writer to run now.
			assertSame(populated,parent.get());
			db.kv().set("local",Strings.create("local write"));
			assertEquals(Strings.create("concurrent write"),db.kv().get("valuable"));
			assertEquals(Strings.create("local write"),db.kv().get("local"));
		}
	}

	@Test
	public void testCreateAndUse() {
		AKeyPair kp = AKeyPair.generate();
		KVDatabase db = KVDatabase.create("test-db", kp);

		assertEquals(Strings.create("test-db"), db.getName());
		assertEquals(kp.getAccountKey(), db.getOwnerKey());

		db.kv().set("key1", Strings.create("value1"));
		assertEquals(Strings.create("value1"), db.kv().get("key1"));
	}

	@Test
	public void testSignedStateHasValidSignature() {
		AKeyPair kp = AKeyPair.generate();
		KVDatabase db = KVDatabase.create("signed-db", kp);

		db.kv().set("foo", Strings.create("bar"));

		var signed = db.getSignedState();
		assertTrue(signed.checkSignature());
		assertEquals(kp.getAccountKey(), signed.getAccountKey());

		// The signed value is a db-name map containing our database
		AHashMap<AString, ?> dbMap = signed.getValue();
		assertNotNull(dbMap.get(Strings.create("signed-db")));
	}

	@Test
	public void testExportReplica() {
		AKeyPair kp = AKeyPair.generate();
		KVDatabase db = KVDatabase.create("export-db", kp);

		db.kv().set("x", Strings.create("y"));

		var exported = db.exportReplica();
		assertEquals(1, exported.count());

		// Entry is keyed by our account key
		var entry = exported.get(kp.getAccountKey());
		assertNotNull(entry);
		assertTrue(entry.checkSignature());
	}

	@Test
	public void testMergeReplicas() {
		AKeyPair keyA = AKeyPair.generate();
		AKeyPair keyB = AKeyPair.generate();

		KVDatabase dbA = KVDatabase.create("shared", keyA, Strings.create("node-a"));
		KVDatabase dbB = KVDatabase.create("shared", keyB, Strings.create("node-b"));

		// Each node writes different keys
		dbA.kv().set("from-a", Strings.create("value-a"));
		dbB.kv().set("from-b", Strings.create("value-b"));

		// Node A merges node B's replica
		long merged = dbA.mergeReplicas(dbB.exportReplica());
		assertEquals(1, merged);

		// Node A now has both keys
		assertEquals(Strings.create("value-a"), dbA.kv().get("from-a"));
		assertEquals(Strings.create("value-b"), dbA.kv().get("from-b"));

		// Node B still only has its own
		assertNull(dbB.kv().get("from-a"));
	}

	@Test
	public void testMergeReplicasBidirectional() {
		AKeyPair keyA = AKeyPair.generate();
		AKeyPair keyB = AKeyPair.generate();

		KVDatabase dbA = KVDatabase.create("shared", keyA);
		KVDatabase dbB = KVDatabase.create("shared", keyB);

		dbA.kv().set("key-a", Strings.create("a"));
		dbB.kv().set("key-b", Strings.create("b"));

		// Both merge each other
		AHashMap<ACell, SignedData<AHashMap<AString, ACell>>> exportA = dbA.exportReplica();
		AHashMap<ACell, SignedData<AHashMap<AString, ACell>>> exportB = dbB.exportReplica();

		dbA.mergeReplicas(exportB);
		dbB.mergeReplicas(exportA);

		// Both converge
		assertEquals(Strings.create("a"), dbA.kv().get("key-a"));
		assertEquals(Strings.create("b"), dbA.kv().get("key-b"));
		assertEquals(Strings.create("a"), dbB.kv().get("key-a"));
		assertEquals(Strings.create("b"), dbB.kv().get("key-b"));
	}

	@Test
	public void testMergeReplicasRejectsForgery() {
		AKeyPair keyA = AKeyPair.generate();
		AKeyPair keyB = AKeyPair.generate();
		AKeyPair keyFake = AKeyPair.generate();

		KVDatabase dbA = KVDatabase.create("secure", keyA);
		KVDatabase dbB = KVDatabase.create("secure", keyB);

		dbB.kv().set("legit", Strings.create("data"));

		// Export B's data but tamper: sign with a different key
		ACell bState = dbB.kv().cursor().get();
		@SuppressWarnings({"unchecked", "rawtypes"})
		AHashMap<AString, ACell> bDbMap =
			(AHashMap) Maps.of(Strings.create("secure"), bState);
		SignedData<AHashMap<AString, ACell>> forged = keyFake.signData(bDbMap);

		// Forge an owner map claiming to be from keyB but signed by keyFake
		@SuppressWarnings({"unchecked", "rawtypes"})
		AHashMap<ACell, SignedData<AHashMap<AString, ACell>>> forgedMap =
			(AHashMap) Maps.of(keyB.getAccountKey(), forged);

		// OwnerLattice rejects: signer (keyFake) doesn't match owner key (keyB)
		long merged = dbA.mergeReplicas(forgedMap);
		assertEquals(0, merged);
		assertNull(dbA.kv().get("legit"));
	}

	@Test
	public void testMergeReplicasSkipsSelf() {
		AKeyPair kp = AKeyPair.generate();
		KVDatabase db = KVDatabase.create("self-test", kp);

		db.kv().set("key", Strings.create("val"));

		// Merging our own export should not count as a merge
		long merged = db.mergeReplicas(db.exportReplica());
		assertEquals(0, merged);
	}

	@Test
	public void testThreeNodeConvergence() {
		AKeyPair keyA = AKeyPair.generate();
		AKeyPair keyB = AKeyPair.generate();
		AKeyPair keyC = AKeyPair.generate();

		KVDatabase dbA = KVDatabase.create("trio", keyA, Strings.create("a"));
		KVDatabase dbB = KVDatabase.create("trio", keyB, Strings.create("b"));
		KVDatabase dbC = KVDatabase.create("trio", keyC, Strings.create("c"));

		// Each node writes different data
		dbA.kv().set("key-a", Strings.create("a"));
		dbA.kv().sadd("shared-set", Strings.create("from-a"));

		dbB.kv().set("key-b", Strings.create("b"));
		dbB.kv().sadd("shared-set", Strings.create("from-b"));

		dbC.kv().set("key-c", Strings.create("c"));
		dbC.kv().sadd("shared-set", Strings.create("from-c"));

		// A merges B and C
		dbA.mergeReplicas(dbB.exportReplica());
		dbA.mergeReplicas(dbC.exportReplica());

		// B merges A (which now includes B+C)
		dbB.mergeReplicas(dbA.exportReplica());

		// C merges B (which now includes A+B+C)
		dbC.mergeReplicas(dbB.exportReplica());

		// All three should converge
		for (KVDatabase db : new KVDatabase[]{dbA, dbB, dbC}) {
			assertEquals(Strings.create("a"), db.kv().get("key-a"));
			assertEquals(Strings.create("b"), db.kv().get("key-b"));
			assertEquals(Strings.create("c"), db.kv().get("key-c"));
			assertEquals(3, db.kv().scard("shared-set"));
			assertTrue(db.kv().sismember("shared-set", Strings.create("from-a")));
			assertTrue(db.kv().sismember("shared-set", Strings.create("from-b")));
			assertTrue(db.kv().sismember("shared-set", Strings.create("from-c")));
		}
	}

	@Test
	public void testConnectedDatabase() {
		// Set up a cursor chain: MapLattice<dbName → KVStore>
		MapLattice<AString, ACell> dbMapLattice =
			MapLattice.create(KVStoreLattice.INSTANCE);
		AForkableCursor<AHashMap<AString, ACell>> root =
			Cursors.root(dbMapLattice.zero());

		// Connect a named database
		KVDatabase db = KVDatabase.connect(root, "mydb");
		assertNotNull(db);
		assertEquals("mydb", db.getName().toString());

		// Use the database
		db.kv().set("greeting", Strings.create("hello"));
		assertEquals(Strings.create("hello"), db.kv().get("greeting"));

		// Verify the root cursor received the update
		AHashMap<AString, ACell> rootMap = root.get();
		assertTrue(rootMap.containsKey(Strings.create("mydb")));

		// Connect a second instance to the same root — sees same data
		KVDatabase db2 = KVDatabase.connect(root, "mydb");
		assertEquals(Strings.create("hello"), db2.kv().get("greeting"));
	}

	@Test
	public void testOwnerLatticeConstant() {
		// Verify OWNER_LATTICE can verify and merge signed data correctly
		AKeyPair kp1 = AKeyPair.generate();
		AKeyPair kp2 = AKeyPair.generate();

		KVDatabase db1 = KVDatabase.create("test", kp1);
		KVDatabase db2 = KVDatabase.create("test", kp2);

		db1.kv().set("k1", Strings.create("v1"));
		db2.kv().set("k2", Strings.create("v2"));

		// Merge both exports through OWNER_LATTICE
		LatticeContext ctx = LatticeContext.create(null, kp1);
		var merged = KVDatabase.OWNER_LATTICE.merge(ctx,
			db1.exportReplica(), db2.exportReplica());

		// Should contain both owners
		assertEquals(2, merged.count());
		assertTrue(merged.containsKey(kp1.getAccountKey()));
		assertTrue(merged.containsKey(kp2.getAccountKey()));
	}

	@Test
	public void testForkAndSyncConnected() {
		// Set up cursor chain
		MapLattice<AString, ACell> dbMapLattice =
			MapLattice.create(KVStoreLattice.INSTANCE);
		AForkableCursor<AHashMap<AString, ACell>> root =
			Cursors.root(dbMapLattice.zero());

		// Connect and populate
		KVDatabase db = KVDatabase.connect(root, "mydb");
		db.kv().set("original", Strings.create("value"));

		// Fork the KV store
		LatticeKV forked = db.kv().fork();
		forked.set("forked", Strings.create("data"));

		// Original unchanged
		assertTrue(db.kv().exists("original"));
		assertFalse(db.kv().exists("forked"));
		assertTrue(forked.exists("forked"));

		// Sync back
		forked.sync();
		assertTrue(db.kv().exists("forked"));
		assertEquals(Strings.create("data"), db.kv().get("forked"));
	}

	@Test
	public void testConnectedModeRejectsStandaloneOps() {
		MapLattice<AString, ACell> dbMapLattice =
			MapLattice.create(KVStoreLattice.INSTANCE);
		AForkableCursor<AHashMap<AString, ACell>> root =
			Cursors.root(dbMapLattice.zero());
		KVDatabase db = KVDatabase.connect(root, "mydb");

		assertNull(db.getKeyPair());
		assertNull(db.getOwnerKey());
		assertThrows(IllegalStateException.class, () -> db.getSignedState());
		assertThrows(IllegalStateException.class, () -> db.exportReplica());

		// mergeReplicas with non-empty map should also throw
		AKeyPair kp = AKeyPair.generate();
		KVDatabase standalone = KVDatabase.create("mydb", kp);
		standalone.kv().set("k", Strings.create("v"));
		var export = standalone.exportReplica();
		assertThrows(IllegalStateException.class, () -> db.mergeReplicas(export));
	}
}
