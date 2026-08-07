package convex.cli.etch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.File;
import java.io.IOException;

import org.junit.jupiter.api.Test;

import convex.cli.CLTester;
import convex.cli.ExitCodes;
import convex.cli.Helpers;
import convex.core.data.ACell;
import convex.core.data.Hash;
import convex.core.data.prim.CVMLong;
import convex.core.util.Utils;

public class EtchCLITest {

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
}
