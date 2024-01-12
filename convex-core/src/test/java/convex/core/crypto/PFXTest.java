package convex.core.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

import org.junit.jupiter.api.Test;

import convex.core.init.InitTest;
import convex.core.lang.RT;

public class PFXTest {

	@Test public void testNewStore() throws IOException, GeneralSecurityException {
		File f=File.createTempFile("temp-keystore", "pfx");
		char[] PASS="test".toCharArray();
		char[] KEYPASS="thehero".toCharArray();
		
		PFXTools.createStore(f, PASS);

		// check password is being applied
		assertThrows(IOException.class,()->PFXTools.loadStore(f,"foobar".toCharArray()));

		// don't throw, no integrity checking on null?
		//assertThrows(IOException.class,()->PFXUtils.loadStore(f,null));

		KeyStore ks=PFXTools.loadStore(f, PASS);
		AKeyPair kp=InitTest.HERO_KEYPAIR;
		PFXTools.setKeyPair(ks, kp, KEYPASS);
		PFXTools.saveStore(ks, f, PASS);

		String alias=InitTest.HERO_KEYPAIR.getAccountKey().toHexString();
		KeyStore ks2=PFXTools.loadStore(f, PASS);
		assertEquals(alias,ks2.aliases().asIterator().next());

		AKeyPair kp2=PFXTools.getKeyPair(ks2,alias, KEYPASS);
		assertEquals(kp.signData(RT.cvm(1L)).getEncoding(),kp2.signData(RT.cvm(1L)).getEncoding());
	}
	
	@Test public void testBadStore() {
		assertThrows(FileNotFoundException.class,()->PFXTools.loadStore(new File("bloooooobiug"), null));
	}
}
