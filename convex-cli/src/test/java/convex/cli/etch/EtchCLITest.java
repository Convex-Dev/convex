package convex.cli.etch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;

import org.junit.jupiter.api.Test;

import convex.cli.CLTester;
import convex.cli.ExitCodes;
import convex.cli.Helpers;
import convex.core.data.ACell;
import convex.core.data.Hash;
import convex.core.data.prim.CVMLong;
import convex.core.util.Utils;
import convex.etch.EtchStore;

public class EtchCLITest {
	@Test
	public void testSnapshotAndCollect() throws IOException {
		File source=Helpers.createTempFile("gcCliSnapshot", ".etch");
		File backup=new File(source.getPath()+".backup");
		backup.deleteOnExit();
		ACell root=convex.core.data.Strings.create("Snapshot root. ".repeat(20));
		ACell garbage=convex.core.data.Strings.create("Snapshot garbage. ".repeat(20));
		try (EtchStore store=EtchStore.create(source)) {
			store.setRootData(root);
			convex.core.data.Cells.persist(garbage,store);
		}
		CLTester gc=CLTester.run("etch","gc","--etch",source.getPath(),"--backup",backup.getPath());
		gc.assertExitCode(ExitCodes.SUCCESS);
		assertTrue(gc.getOutput().contains("Backup file:"));
		assertFalse(gc.getOutput().contains("Reclaimed:"));
		try (EtchStore live=EtchStore.create(source); EtchStore snapshot=EtchStore.create(backup)) {
			assertEquals(root,live.getRootData());
			assertEquals(root,snapshot.getRootData());
			assertNull(live.getEtch().read(garbage.getHash()));
			assertNotNull(snapshot.getEtch().read(garbage.getHash()));
		}
		byte[] before=Files.readAllBytes(backup.toPath());
		CLTester overwrite=CLTester.run("etch","gc","--etch",source.getPath(),"--backup",backup.getPath());
		assertEquals(ExitCodes.ERROR,overwrite.getResult());
		org.junit.jupiter.api.Assertions.assertArrayEquals(before,Files.readAllBytes(backup.toPath()));
		CLTester conflicting=CLTester.run("etch","gc","--etch",source.getPath(),
				"--backup",backup.getPath(),"--output",source.getPath()+".out");
		assertEquals(ExitCodes.ERROR,conflicting.getResult());
	}

	@Test
	public void testMissingRootRejectedInBothGCModes() throws IOException {
		File source=Helpers.createTempFile("gcCliMissingRoot", ".etch");
		Hash missing=convex.core.data.Strings.create("Absent root. ".repeat(20)).getHash();
		try (EtchStore store=EtchStore.create(source)) {
			store.getEtch().setRootHash(missing);
		}
		CLTester inPlace=CLTester.run("etch","gc","--etch",source.getPath());
		assertEquals(ExitCodes.ERROR,inPlace.getResult());
		assertTrue(inPlace.getError().contains("missing data"));
		File output=new File(source.getPath()+".out");
		CLTester toOutput=CLTester.run("etch","gc","--etch",source.getPath(),"--output",output.getPath());
		assertEquals(ExitCodes.ERROR,toOutput.getResult());
		assertFalse(output.exists());
		try (EtchStore store=EtchStore.create(source)) {
			assertEquals(missing,store.getRootHash());
		}
	}

	private static final File TEMP_ETCH;
	private static final ACell NUM=CVMLong.create(123);
	private static final Hash HASH=NUM.getHash();
	private static final String EXPECTED="0x9a14ff887ac692b3c0854638b2178c7f3acceb4ace6fb9fd6abb75e5e1d6d7da";
	
	static {
		try {
			TEMP_ETCH=Helpers.createTempFile("tempEtchDatabase", ".db");
			
		} catch (Exception t) {
			throw Utils.sneakyThrow(t);
		} 
		
	}
	
	@Test
	public void testEtchGCMigrateRecover() throws IOException {
		File f=Helpers.createTempFile("gcCliEtch", ".db");

		// Seed: a root tree plus an unreachable (garbage) value
		convex.core.data.AString big=convex.core.data.Strings.create("GC CLI test root value. ".repeat(10));
		convex.core.data.AVector<ACell> root=convex.core.data.Vectors.of(big, CVMLong.create(7));
		convex.core.data.AString garbage=convex.core.data.Strings.create("GC CLI garbage value. ".repeat(10));
		Hash rootHash=root.getHash();
		Hash garbageHash=garbage.getHash();
		{
			convex.etch.EtchStore s=convex.etch.EtchStore.create(f);
			convex.core.data.Cells.persist(garbage, s);
			s.setRootData(root);
			s.flush();
			s.close();
		}

		// In-place GC
		CLTester t=CLTester.run("etch", "gc", "--etch", f.getCanonicalPath());
		t.assertExitCode(ExitCodes.SUCCESS);

		// Collected store: root intact, garbage gone. (The data may still live
		// on the generational file until adoption completes; EtchStore.create
		// resolves that transparently)
		{
			convex.etch.EtchStore s=convex.etch.EtchStore.create(f);
			assertEquals(rootHash, s.getRootHash());
			assertEquals(root, s.getRootData());
			assertNull(s.getEtch().read(garbageHash));
			s.close();
		}

		// Migrate into a fresh destination, adopting the source root
		File dest=Helpers.createTempFile("gcCliDest", ".db");
		t=CLTester.run("etch", "migrate", "--etch", f.getCanonicalPath(),
				"--into", dest.getCanonicalPath(), "--set-root");
		t.assertExitCode(ExitCodes.SUCCESS);
		{
			convex.etch.EtchStore d=convex.etch.EtchStore.create(dest);
			assertEquals(rootHash, d.getRootHash());
			assertEquals(root, d.getRootData());
			d.close();
		}

		// Explicit recovery run reports success
		t=CLTester.run("etch", "recover", "--etch", f.getCanonicalPath());
		t.assertExitCode(ExitCodes.SUCCESS);

		// Offline repair reconstructs a fresh, independently verified store.
		File repaired=Helpers.createTempFile("gcCliRepair", ".db");
		t=CLTester.run("etch", "repair", "--etch", f.getCanonicalPath(),
				"--into", repaired.getCanonicalPath());
		t.assertExitCode(ExitCodes.SUCCESS);
		{
			convex.etch.EtchStore r=convex.etch.EtchStore.create(repaired);
			assertEquals(rootHash,r.getRootHash());
			assertEquals(root,r.getRootData());
			r.close();
		}

		// GC --output: collect into a fresh file, source untouched
		File out=Helpers.createTempFile("gcCliOut", ".db");
		t=CLTester.run("etch", "gc", "--etch", dest.getCanonicalPath(),
				"--output", out.getCanonicalPath());
		t.assertExitCode(ExitCodes.SUCCESS);
		{
			convex.etch.EtchStore o=convex.etch.EtchStore.create(out);
			assertEquals(rootHash, o.getRootHash());
			assertEquals(root, o.getRootData());
			o.close();
		}
	}

	@Test
	public void testEtch() throws IOException {
		assertNotNull(TEMP_ETCH);
		
		CLTester tester =  CLTester.run(
				"etch", "info",
				"--etch",TEMP_ETCH.getCanonicalPath()
		);
		tester.assertExitCode(ExitCodes.SUCCESS);
		
		tester =  CLTester.run(
				"etch", "write",
				"--etch",TEMP_ETCH.getCanonicalPath(),
				"-c", "123"
		);
		tester.assertExitCode(ExitCodes.SUCCESS);
		assertEquals(HASH,Hash.parse(tester.getOutput()));
		assertEquals(EXPECTED,tester.getOutput().trim());
		
		tester =  CLTester.run(
				"etch", "read",
				"--etch",TEMP_ETCH.getCanonicalPath(),
				HASH.toHexString()
		);
		tester.assertExitCode(ExitCodes.SUCCESS);
		assertEquals("123",tester.getOutput().trim());

	}

	@Test
	public void testStrictValidateCommand() throws IOException {
		File file=Helpers.createTempFile("strictValidateCli", ".db");
		convex.core.data.AString root=convex.core.data.Strings.create(
				"Strict CLI validation root. ".repeat(12));
		convex.etch.EtchStore store=convex.etch.EtchStore.create(file);
		store.setRootData(root);
		store.flush();
		store.close();

		CLTester tester=CLTester.run("etch", "validate", "--etch", file.getCanonicalPath());
		tester.assertExitCode(ExitCodes.SUCCESS);
		assertTrue(tester.getOutput().contains("strict validation completed with 0 error(s)"));

		long position;
		try (convex.etch.EtchMaintenanceReader reader=
				convex.etch.EtchMaintenanceReader.openUnsafe(file)) {
			long slot=reader.getIndexStart()
					+(root.getHash().shortAt(0)&0xffffL)*convex.etch.EtchConstants.POINTER_SIZE;
			position=reader.readIndexSlot(slot)&~convex.etch.EtchConstants.POINTER_TYPE_MASK;
		}
		try (RandomAccessFile data=new RandomAccessFile(file,"rw")) {
			long encoding=position+convex.etch.EtchConstants.KEY_SIZE
					+convex.etch.EtchConstants.LABEL_SIZE
					+convex.etch.EtchConstants.ENCODING_LENGTH_SIZE;
			data.seek(encoding);
			int value=data.readUnsignedByte();
			data.seek(encoding);
			data.writeByte(value^1);
		}

		tester=CLTester.run("etch", "validate", "--etch", file.getCanonicalPath(),
				"--max-failures", "1");
		assertEquals(ExitCodes.ERROR,tester.getResult());
		assertTrue(tester.getOutput().contains("strict validation completed with 2 error(s)"));
	}
}
