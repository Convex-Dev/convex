package convex.core.crypto;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;

import org.junit.jupiter.api.Test;

import convex.core.crypto.wallet.PKCS12Wallet;
import convex.core.crypto.wallet.HotWalletEntry;
import convex.core.lang.RT;

public class WalletTest {
	
	@Test 
	public void testTempStore() {
		String password="OmarSharif";
		File file=PKCS12Wallet.createTempStore(password);
		assertNotNull(file);
	}

	@Test
	public void testLockedEntryCannotSign() {
		AKeyPair kp=AKeyPair.createSeeded(1234);
		HotWalletEntry we=HotWalletEntry.create(kp,"test");
		assertEquals(kp,we.getKeyPair());

		we.lock("hunter2".toCharArray());
		// Locking must gate signing, not just key access
		assertThrows(IllegalStateException.class,()->we.getKeyPair());
		assertThrows(IllegalStateException.class,()->we.sign(RT.cvm(1L)));

		assertTrue(we.tryUnlock("hunter2".toCharArray()));
		assertNotNull(we.sign(RT.cvm(1L)));
	}

}
