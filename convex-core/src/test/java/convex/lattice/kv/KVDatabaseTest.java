package convex.lattice.kv;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Index;
import convex.core.data.Maps;
import convex.core.data.SignedData;
import convex.core.data.Strings;
import convex.lattice.LatticeContext;
import convex.lattice.cursor.ALatticeCursor;
import convex.lattice.cursor.Cursors;
import convex.lattice.generic.MapLattice;

/**
 * Tests for KVDatabase — signed, replicated KV stores in the global lattice.
 */
public class KVDatabaseTest {

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
		AHashMap<ACell, SignedData<AHashMap<AString, Index<AString, AVector<ACell>>>>> exportA = dbA.exportReplica();
		AHashMap<ACell, SignedData<AHashMap<AString, Index<AString, AVector<ACell>>>>> exportB = dbB.exportReplica();

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
		Index<AString, AVector<ACell>> bState = dbB.kv().cursor().get();
		@SuppressWarnings({"unchecked", "rawtypes"})
		AHashMap<AString, Index<AString, AVector<ACell>>> bDbMap =
			(AHashMap) Maps.of(Strings.create("secure"), bState);
		SignedData<AHashMap<AString, Index<AString, AVector<ACell>>>> forged = keyFake.signData(bDbMap);

		// Forge an owner map claiming to be from keyB but signed by keyFake
		@SuppressWarnings({"unchecked", "rawtypes"})
		AHashMap<ACell, SignedData<AHashMap<AString, Index<AString, AVector<ACell>>>>> forgedMap =
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
		MapLattice<AString, Index<AString, AVector<ACell>>> dbMapLattice =
			MapLattice.create(KVStoreLattice.INSTANCE);
		ALatticeCursor<AHashMap<AString, Index<AString, AVector<ACell>>>> root =
			Cursors.createLattice(dbMapLattice);

		// Connect a named database
		KVDatabase db = KVDatabase.connect(root, "mydb");
		assertNotNull(db);
		assertEquals("mydb", db.getName().toString());

		// Use the database
		db.kv().set("greeting", Strings.create("hello"));
		assertEquals(Strings.create("hello"), db.kv().get("greeting"));

		// Verify the root cursor received the update
		AHashMap<AString, Index<AString, AVector<ACell>>> rootMap = root.get();
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
		MapLattice<AString, Index<AString, AVector<ACell>>> dbMapLattice =
			MapLattice.create(KVStoreLattice.INSTANCE);
		ALatticeCursor<AHashMap<AString, Index<AString, AVector<ACell>>>> root =
			Cursors.createLattice(dbMapLattice);

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
		MapLattice<AString, Index<AString, AVector<ACell>>> dbMapLattice =
			MapLattice.create(KVStoreLattice.INSTANCE);
		ALatticeCursor<AHashMap<AString, Index<AString, AVector<ACell>>>> root =
			Cursors.createLattice(dbMapLattice);
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
