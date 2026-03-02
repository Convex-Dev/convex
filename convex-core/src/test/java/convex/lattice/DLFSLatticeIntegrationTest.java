package convex.lattice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.cvm.Keywords;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.data.Maps;
import convex.core.data.SignedData;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.lattice.fs.DLFSNode;

/**
 * Integration tests for DLFS in the ROOT lattice structure.
 *
 * These tests verify that DLFS works correctly within the lattice framework,
 * including merge semantics, signature validation, and multi-owner scenarios.
 */
public class DLFSLatticeIntegrationTest {

	/**
	 * Tests basic DLFS merge within ROOT lattice structure.
	 * Verifies that two DLFS nodes from different owners can be merged.
	 */
	@Test
	public void testBasicDLFSMerge() {
		// Create two keypairs for two different owners
		AKeyPair owner1Key = AKeyPair.generate();
		AKeyPair owner2Key = AKeyPair.generate();

		// Owner 1 creates a DLFS filesystem with a file
		CVMLong timestamp1 = CVMLong.create(1000);
		AVector<ACell> dir1 = DLFSNode.createDirectory(timestamp1);

		// Add a file to owner1's directory
		AString fileName = Strings.create("test.txt");
		AVector<ACell> file1 = DLFSNode.createEmptyFile(timestamp1);
		Blob testData1 = Blob.fromHex("48656c6c6f"); // "Hello" in hex
		file1 = file1.assoc(DLFSNode.POS_DATA, testData1);

		Index<AString, AVector<ACell>> entries1 = Index.of(fileName, file1);
		dir1 = dir1.assoc(DLFSNode.POS_DIR, entries1);

		// Create the drive map: {"main" -> dir1}
		AString driveName = Strings.create("main");
		AHashMap<AString, AVector<ACell>> driveMap1 = Maps.of(driveName, dir1);

		// Sign owner1's drive map
		SignedData<AHashMap<AString, AVector<ACell>>> signedDriveMap1 = owner1Key.signData(driveMap1);

		// Create the structure: owner1 -> signedDriveMap1
		AHashMap<ACell, SignedData<AHashMap<AString, AVector<ACell>>>> fsMap1 =
			Maps.of(owner1Key.getAccountKey(), signedDriveMap1);

		// Owner 2 creates a different DLFS filesystem with a different file
		CVMLong timestamp2 = CVMLong.create(2000);
		AVector<ACell> dir2 = DLFSNode.createDirectory(timestamp2);

		// Add a different file to owner2's directory
		AString fileName2 = Strings.create("other.txt");
		AVector<ACell> file2 = DLFSNode.createEmptyFile(timestamp2);
		Blob testData2 = Blob.fromHex("576f726c64"); // "World" in hex
		file2 = file2.assoc(DLFSNode.POS_DATA, testData2);

		Index<AString, AVector<ACell>> entries2 = Index.of(fileName2, file2);
		dir2 = dir2.assoc(DLFSNode.POS_DIR, entries2);

		// Create the drive map: {"main" -> dir2}
		AHashMap<AString, AVector<ACell>> driveMap2 = Maps.of(driveName, dir2);

		// Sign owner2's drive map
		SignedData<AHashMap<AString, AVector<ACell>>> signedDriveMap2 = owner2Key.signData(driveMap2);

		// Create the structure: owner2 -> signedDriveMap2
		AHashMap<ACell, SignedData<AHashMap<AString, AVector<ACell>>>> fsMap2 =
			Maps.of(owner2Key.getAccountKey(), signedDriveMap2);

		// Get the :fs lattice from ROOT and merge
		ALattice<ACell> fsLattice = Lattice.ROOT.path(Keywords.FS);
		ACell mergedValue = fsLattice.merge(fsMap1, fsMap2);

		// Cast and verify the merge result contains both owners
		assertNotNull(mergedValue, "Merged FS value should not be null");
		assertTrue(mergedValue instanceof AHashMap, "Merged FS should be a HashMap");

		@SuppressWarnings("unchecked")
		AHashMap<ACell, SignedData<AHashMap<AString, AVector<ACell>>>> mergedFS =
			(AHashMap<ACell, SignedData<AHashMap<AString, AVector<ACell>>>>) mergedValue;

		assertEquals(2, mergedFS.count(), "Should have 2 owners after merge");
		assertTrue(mergedFS.containsKey(owner1Key.getAccountKey()), "Should contain owner1");
		assertTrue(mergedFS.containsKey(owner2Key.getAccountKey()), "Should contain owner2");

		// Verify owner1's data is intact
		SignedData<AHashMap<AString, AVector<ACell>>> owner1Data = mergedFS.get(owner1Key.getAccountKey());
		assertNotNull(owner1Data, "Owner1 data should exist");
		assertTrue(owner1Data.checkSignature(), "Owner1 signature should be valid");
		AHashMap<AString, AVector<ACell>> owner1DriveMap = owner1Data.getValue();
		AVector<ACell> owner1Dir = owner1DriveMap.get(driveName);
		assertNotNull(owner1Dir, "Owner1 drive should exist");

		// Verify owner2's data is intact
		SignedData<AHashMap<AString, AVector<ACell>>> owner2Data = mergedFS.get(owner2Key.getAccountKey());
		assertNotNull(owner2Data, "Owner2 data should exist");
		assertTrue(owner2Data.checkSignature(), "Owner2 signature should be valid");
		AHashMap<AString, AVector<ACell>> owner2DriveMap = owner2Data.getValue();
		AVector<ACell> owner2Dir = owner2DriveMap.get(driveName);
		assertNotNull(owner2Dir, "Owner2 drive should exist");
	}

	/**
	 * Tests DLFS merge with same owner updating their drive.
	 * Verifies that newer timestamps win during merge.
	 */
	@Test
	public void testSameOwnerDLFSMerge() {
		AKeyPair ownerKey = AKeyPair.generate();
		AString driveName = Strings.create("main");

		// Node 1: Owner creates initial filesystem at timestamp 1000
		CVMLong timestamp1 = CVMLong.create(1000);
		AVector<ACell> dir1 = DLFSNode.createDirectory(timestamp1);
		AString fileName = Strings.create("file.txt");
		AVector<ACell> file1 = DLFSNode.createEmptyFile(timestamp1);
		file1 = file1.assoc(DLFSNode.POS_DATA, Blob.fromHex("56657273696f6e2031")); // "Version 1"

		Index<AString, AVector<ACell>> entries1 = Index.of(fileName, file1);
		dir1 = dir1.assoc(DLFSNode.POS_DIR, entries1);

		AHashMap<AString, AVector<ACell>> driveMap1 = Maps.of(driveName, dir1);
		SignedData<AHashMap<AString, AVector<ACell>>> signedDriveMap1 = ownerKey.signData(driveMap1);
		AHashMap<ACell, SignedData<AHashMap<AString, AVector<ACell>>>> fsMap1 =
			Maps.of(ownerKey.getAccountKey(), signedDriveMap1);

		// Node 2: Owner updates the same file at timestamp 2000 (newer)
		CVMLong timestamp2 = CVMLong.create(2000);
		AVector<ACell> dir2 = DLFSNode.createDirectory(timestamp2);
		AVector<ACell> file2 = DLFSNode.createEmptyFile(timestamp2);
		file2 = file2.assoc(DLFSNode.POS_DATA, Blob.fromHex("56657273696f6e2032")); // "Version 2"

		Index<AString, AVector<ACell>> entries2 = Index.of(fileName, file2);
		dir2 = dir2.assoc(DLFSNode.POS_DIR, entries2);

		AHashMap<AString, AVector<ACell>> driveMap2 = Maps.of(driveName, dir2);
		SignedData<AHashMap<AString, AVector<ACell>>> signedDriveMap2 = ownerKey.signData(driveMap2);
		AHashMap<ACell, SignedData<AHashMap<AString, AVector<ACell>>>> fsMap2 =
			Maps.of(ownerKey.getAccountKey(), signedDriveMap2);

		// Get the :fs lattice and merge fsMap2 (newer) into fsMap1 (older)
		ALattice<ACell> fsLattice = Lattice.ROOT.path(Keywords.FS);
		ACell mergedValue = fsLattice.merge(fsMap1, fsMap2);

		@SuppressWarnings("unchecked")
		AHashMap<ACell, SignedData<AHashMap<AString, AVector<ACell>>>> mergedFS =
			(AHashMap<ACell, SignedData<AHashMap<AString, AVector<ACell>>>>) mergedValue;

		// Verify only one owner exists
		assertEquals(1, mergedFS.count(), "Should have 1 owner");

		SignedData<AHashMap<AString, AVector<ACell>>> ownerData = mergedFS.get(ownerKey.getAccountKey());
		AHashMap<AString, AVector<ACell>> ownerDriveMap = ownerData.getValue();
		AVector<ACell> mergedDir = ownerDriveMap.get(driveName);

		// Check timestamp - should be the newer one (2000)
		CVMLong mergedTimestamp = DLFSNode.getUTime(mergedDir);
		assertEquals(timestamp2.longValue(), mergedTimestamp.longValue(),
			"Merged directory should have newer timestamp");

		// Verify file exists with the newer content
		Index<AString, AVector<ACell>> mergedEntries = DLFSNode.getDirectoryEntries(mergedDir);
		AVector<ACell> mergedFile = mergedEntries.get(fileName);
		assertNotNull(mergedFile, "Merged file should exist");

		// Verify the data is from the newer version
		Blob fileData = (Blob) DLFSNode.getData(mergedFile);
		assertEquals(Blob.fromHex("56657273696f6e2032"), fileData,
			"Merged file should have newer content");
	}

	/**
	 * Tests that ROOT lattice has both :data and :fs paths.
	 */
	@Test
	public void testRootLatticePaths() {
		// Verify ROOT lattice has both :data and :fs keywords
		assertNotNull(Lattice.ROOT, "ROOT lattice should exist");
		assertNotNull(Lattice.ROOT.path(Keywords.DATA), ":data sublattice should exist");
		assertNotNull(Lattice.ROOT.path(Keywords.FS), ":fs sublattice should exist");
	}

	/**
	 * Tests that OwnerLattice rejects forged data signed by an attacker
	 * but placed under the legitimate owner's key.
	 */
	@Test
	public void testForgeryRejection() {
		AKeyPair legitimateKey = AKeyPair.generate();
		AKeyPair attackerKey = AKeyPair.generate();

		// Legitimate owner creates a DLFS tree and signs it
		CVMLong timestamp = CVMLong.create(1000);
		AVector<ACell> dir = DLFSNode.createDirectory(timestamp);
		AString fileName = Strings.create("important.txt");
		AVector<ACell> file = DLFSNode.createEmptyFile(timestamp);
		file = file.assoc(DLFSNode.POS_DATA, Blob.fromHex("4c656769746d617465")); // "Legitmate"

		Index<AString, AVector<ACell>> entries = Index.of(fileName, file);
		dir = dir.assoc(DLFSNode.POS_DIR, entries);

		AString driveName = Strings.create("main");
		AHashMap<AString, AVector<ACell>> driveMap = Maps.of(driveName, dir);
		SignedData<AHashMap<AString, AVector<ACell>>> signedDriveMap = legitimateKey.signData(driveMap);

		// Legitimate owner's FS map
		AHashMap<ACell, SignedData<AHashMap<AString, AVector<ACell>>>> legitimateFS =
			Maps.of(legitimateKey.getAccountKey(), signedDriveMap);

		// Attacker creates forged data and signs with THEIR key,
		// but places it under the LEGITIMATE owner's key
		CVMLong attackerTimestamp = CVMLong.create(9999); // newer timestamp to try to win merge
		AVector<ACell> forgedDir = DLFSNode.createDirectory(attackerTimestamp);
		AVector<ACell> forgedFile = DLFSNode.createEmptyFile(attackerTimestamp);
		forgedFile = forgedFile.assoc(DLFSNode.POS_DATA, Blob.fromHex("466f72676564")); // "Forged"

		Index<AString, AVector<ACell>> forgedEntries = Index.of(fileName, forgedFile);
		forgedDir = forgedDir.assoc(DLFSNode.POS_DIR, forgedEntries);

		AHashMap<AString, AVector<ACell>> forgedDriveMap = Maps.of(driveName, forgedDir);
		// Attacker signs with their own key — the signature is valid, but the signer doesn't match the owner
		SignedData<AHashMap<AString, AVector<ACell>>> forgedSignedData = attackerKey.signData(forgedDriveMap);

		// Place forgery under the legitimate owner's key
		AHashMap<ACell, SignedData<AHashMap<AString, AVector<ACell>>>> forgedFS =
			Maps.of(legitimateKey.getAccountKey(), forgedSignedData);

		// Merge with context-aware OwnerLattice — forgery should be rejected
		ALattice<ACell> fsLattice = Lattice.ROOT.path(Keywords.FS);
		LatticeContext context = LatticeContext.create(null, legitimateKey);
		ACell mergedValue = fsLattice.merge(context, legitimateFS, forgedFS);

		@SuppressWarnings("unchecked")
		AHashMap<ACell, SignedData<AHashMap<AString, AVector<ACell>>>> mergedFS =
			(AHashMap<ACell, SignedData<AHashMap<AString, AVector<ACell>>>>) mergedValue;

		// Should still have the legitimate owner's entry
		assertEquals(1, mergedFS.count(), "Should have exactly 1 owner");
		SignedData<AHashMap<AString, AVector<ACell>>> ownerData = mergedFS.get(legitimateKey.getAccountKey());
		assertNotNull(ownerData, "Legitimate owner data should survive");

		// Verify the data is the legitimate data, not the forgery
		assertTrue(ownerData.checkSignature(), "Signature should be valid");
		assertEquals(legitimateKey.getAccountKey(), ownerData.getAccountKey(),
			"Signer should be the legitimate owner");
		AHashMap<AString, AVector<ACell>> ownerDriveMap = ownerData.getValue();
		AVector<ACell> resultDir = ownerDriveMap.get(driveName);
		assertNotNull(resultDir, "Drive should exist");

		// Timestamp should be the legitimate one (1000), not the forged one (9999)
		CVMLong resultTimestamp = DLFSNode.getUTime(resultDir);
		assertEquals(1000L, resultTimestamp.longValue(),
			"Should have legitimate timestamp, not forged");

		// File data should be legitimate
		Index<AString, AVector<ACell>> resultEntries = DLFSNode.getDirectoryEntries(resultDir);
		AVector<ACell> resultFile = resultEntries.get(fileName);
		Blob resultData = (Blob) DLFSNode.getData(resultFile);
		assertEquals(Blob.fromHex("4c656769746d617465"), resultData,
			"Should have legitimate data, forgery should be rejected");
	}
}
