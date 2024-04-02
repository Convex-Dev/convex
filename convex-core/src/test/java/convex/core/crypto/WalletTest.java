package convex.core.crypto;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.File;

import org.junit.jupiter.api.Test;

import convex.core.crypto.wallet.PKCS12Wallet;

public class WalletTest {
	
	@Test 
	public void testTempStore() {
		String password="OmarSharif";
		File file=PKCS12Wallet.createTempStore(password);
		assertNotNull(file);
	}
}
