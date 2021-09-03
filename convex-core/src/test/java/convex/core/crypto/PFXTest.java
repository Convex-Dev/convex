package convex.core.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.File;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

import org.junit.jupiter.api.Test;

import convex.core.init.InitTest;
import convex.core.lang.RT;

public class PFXTest {

	@Test public void testNewStore() throws IOException, GeneralSecurityException {
		File f=File.createTempFile("temp-keystore", "pfx");

		PFXTools.createStore(f, "test");

		// check password is being applied
		assertThrows(IOException.class,()->PFXTools.loadStore(f,"foobar"));

		// don't throw, no integrity checking on null?
		//assertThrows(IOException.class,()->PFXUtils.loadStore(f,null));

		KeyStore ks=PFXTools.loadStore(f, "test");
		AKeyPair kp=InitTest.HERO_KEYPAIR;
		PFXTools.setKeyPair(ks, kp, "thehero");
		PFXTools.saveStore(ks, f, "test");

		String alias=InitTest.HERO_KEYPAIR.getAccountKey().toHexString();
		KeyStore ks2=PFXTools.loadStore(f, "test");
		assertEquals(alias,ks2.aliases().asIterator().next());

		AKeyPair kp2=PFXTools.getKeyPair(ks2,alias, "thehero");
		assertEquals(kp.signData(RT.cvm(1L)).getEncoding(),kp2.signData(RT.cvm(1L)).getEncoding());
	}
}
