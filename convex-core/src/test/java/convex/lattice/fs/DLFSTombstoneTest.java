package convex.lattice.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Index;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.lattice.fs.impl.DLFSLocal;

/**
 * Tests for the split node structure: live children in POS_DIR, deletions recorded as
 * tombstones (name to deletion timestamp) in the optional POS_TOMBS index.
 */
public class DLFSTombstoneTest {

	/** A directory node carries the POS_TOMBS element if and only if it has tombstones. */
	@Test
	public void testCanonicalNodeShape() throws IOException {
		DLFSLocal fs = DLFS.create();
		fs.setTimestamp(CVMLong.create(100));
		DLPath root = fs.getRoot();
		Path f = Files.createFile(root.resolve("f.txt"));

		// No deletions yet: plain 4-element directory node
		AVector<ACell> r0 = fs.getNode(root);
		assertEquals(DLFSNode.NODE_LENGTH, r0.count());
		assertTrue(DLFSNode.getTombstones(r0).isEmpty());

		// Delete records a tombstone with the deletion timestamp; node grows to 5 elements
		fs.setTimestamp(CVMLong.create(200));
		Files.delete(f);
		AVector<ACell> r1 = fs.getNode(root);
		assertEquals(5L, r1.count());
		Index<AString, CVMLong> tombs = DLFSNode.getTombstones(r1);
		assertEquals(1L, tombs.count());
		assertEquals(CVMLong.create(200), tombs.get(Strings.create("f.txt")));
		assertTrue(DLFSNode.isEmpty(r1));        // no live children
		assertFalse(Files.exists(f));

		// Recreating clears the tombstone, returning the node to canonical 4-element form
		fs.setTimestamp(CVMLong.create(300));
		Files.createFile(root.resolve("f.txt"));
		AVector<ACell> r2 = fs.getNode(root);
		assertEquals(DLFSNode.NODE_LENGTH, r2.count());
		assertTrue(DLFSNode.getTombstones(r2).isEmpty());
		assertTrue(Files.exists(root.resolve("f.txt")));
	}

	/** A creation newer than a concurrent deletion wins after merge, on both replicas. */
	@Test
	public void testNewerCreateBeatsDelete() throws IOException {
		DLFSLocal a = DLFS.create();
		DLFSLocal b = DLFS.create();

		// A holds a fresh file created at t=200
		a.setTimestamp(CVMLong.create(200));
		Files.write(a.getPath("/x"), new byte[] { 9 });

		// B created then deleted the same name earlier: it holds only a tombstone at t=100
		b.setTimestamp(CVMLong.create(50));
		Files.write(b.getPath("/x"), new byte[] { 1 });
		b.setTimestamp(CVMLong.create(100));
		Files.delete(b.getPath("/x"));

		a.replicate(b);
		b.replicate(a);

		// 200 > 100: the file survives on both, and the replicas converge
		assertTrue(Files.exists(a.getPath("/x")));
		assertTrue(Files.exists(b.getPath("/x")));
		assertEquals(a.getRootHash(), b.getRootHash());
	}

	/** A deletion newer than a concurrent creation wins after merge, on both replicas. */
	@Test
	public void testNewerDeleteBeatsCreate() throws IOException {
		DLFSLocal a = DLFS.create();
		DLFSLocal b = DLFS.create();

		// A holds a file created at t=100
		a.setTimestamp(CVMLong.create(100));
		Files.write(a.getPath("/y"), new byte[] { 9 });

		// B deleted the name at t=200
		b.setTimestamp(CVMLong.create(50));
		Files.write(b.getPath("/y"), new byte[] { 1 });
		b.setTimestamp(CVMLong.create(200));
		Files.delete(b.getPath("/y"));

		a.replicate(b);
		b.replicate(a);

		// 200 > 100: the deletion wins on both, and the replicas converge
		assertFalse(Files.exists(a.getPath("/y")));
		assertFalse(Files.exists(b.getPath("/y")));
		assertEquals(a.getRootHash(), b.getRootHash());
	}

	/** Merge order does not matter: bidirectional replication converges to one canonical tree. */
	@Test
	public void testMergeConverges() throws IOException {
		DLFSLocal a = DLFS.create();
		DLFSLocal b = DLFS.create();

		a.setTimestamp(CVMLong.create(10));
		Files.createDirectory(a.getPath("/d"));
		Files.write(a.getPath("/d/keep"), new byte[] { 1 });
		Files.write(a.getPath("/gone"), new byte[] { 2 });

		b.replicate(a); // both share the baseline

		a.setTimestamp(CVMLong.create(20));
		Files.delete(a.getPath("/gone"));       // A deletes gone
		b.setTimestamp(CVMLong.create(20));
		Files.write(b.getPath("/d/added"), new byte[] { 3 }); // B adds a file concurrently

		a.replicate(b);
		b.replicate(a);

		assertEquals(a.getRootHash(), b.getRootHash());
		assertFalse(Files.exists(a.getPath("/gone")));
		assertTrue(Files.exists(a.getPath("/d/keep")));
		assertTrue(Files.exists(a.getPath("/d/added")));
	}
}
