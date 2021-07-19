package convex.core.crypto;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.File;

import org.junit.jupiter.api.Test;

public class WalletTest {
	
	@Test 
	public void testTempStore() {
		String password="OmarSharif";
		File file=Wallet.createTempStore(password);
		assertNotNull(file);
	}
}
