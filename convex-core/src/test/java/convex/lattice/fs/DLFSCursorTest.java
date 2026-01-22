package convex.lattice.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.lattice.cursor.ABranchedCursor;
import convex.lattice.cursor.Root;
import convex.lattice.fs.impl.DLFSLocal;

/**
 * Tests for DLFS integration with lattice cursors.
 *
 * Demonstrates clean API for:
 * - Creating DLFS drives backed by lattice cursors
 * - Detaching cursors for isolated operations
 * - Syncing changes back to the lattice
 */
public class DLFSCursorTest {

	@Test
	public void testDLFSWithCursorSync() throws Exception {
		// Create a lattice root cursor with a simple map structure
		// Structure: {"test" -> DLFSNode}
		Root<AHashMap<AString, AVector<ACell>>> root = Root.create(Maps.empty());

		// Get branched cursor for "test" drive
		AString driveName = Strings.create("test");
		ABranchedCursor<AVector<ACell>> driveCursor = root.path(driveName);

		// Initialize with empty DLFS tree if needed
		if (driveCursor.get() == null) {
			driveCursor.set(DLFSNode.createDirectory(CVMLong.ZERO));
		}

		// Detach cursor for isolated DLFS operations
		ABranchedCursor<AVector<ACell>> detached = driveCursor.detach();
		DLFSLocal dlfs = new DLFSLocal(DLFS.provider(), null, detached);

		// Create directory
		Path testDir = dlfs.getPath("/docs");
		Files.createDirectory(testDir);

		// Write file
		Path file = dlfs.getPath("/docs/readme.txt");
		Files.writeString(file, "Hello from DLFS!");

		// Sync the DLFS drive back to root lattice
		boolean synced = driveCursor.sync(detached);
		assertTrue(synced, "Sync should succeed");

		// Verify: Create new DLFS from synced root
		AVector<ACell> syncedTree = root.get().get(driveName);
		DLFSLocal dlfs2 = new DLFSLocal(DLFS.provider(), null, syncedTree);

		// Verify file exists and has correct content
		Path verifyFile = dlfs2.getPath("/docs/readme.txt");
		assertTrue(Files.exists(verifyFile), "File should exist after sync");
		assertEquals("Hello from DLFS!", Files.readString(verifyFile));
	}
}
