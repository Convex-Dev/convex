package convex.cli.etch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
