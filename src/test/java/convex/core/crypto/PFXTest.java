package convex.core.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.File;
import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.SignatureException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;

import org.junit.jupiter.api.Test;

import convex.core.lang.RT;
import convex.core.lang.TestState;

public class PFXTest {

	@Test public void testNewStore() throws IOException, KeyStoreException, NoSuchAlgorithmException, CertificateException, InvalidKeyException, SecurityException, SignatureException, UnrecoverableKeyException {
		File f=File.createTempFile("temp-keystore", "pfx");

		PFXTools.createStore(f, "test");

		// check password is being applied
		assertThrows(IOException.class,()->PFXTools.loadStore(f,"foobar"));

		// don't throw, no integrity checking on null?
		//assertThrows(IOException.class,()->PFXUtils.loadStore(f,null));

		KeyStore ks=PFXTools.loadStore(f, "test");
		AKeyPair kp=TestState.HERO_KEYPAIR;
		PFXTools.saveKey(ks, kp, "thehero");
		PFXTools.saveStore(ks, f, "test");

		String alias=TestState.HERO_KEYPAIR.getAccountKey().toHexString();
		KeyStore ks2=PFXTools.loadStore(f, "test");
		assertEquals(alias,ks2.aliases().asIterator().next());

		AKeyPair kp2=PFXTools.getKeyPair(ks2,alias, "thehero");
		assertEquals(kp.signData(RT.cvm(1L)).getEncoding(),kp2.signData(RT.cvm(1L)).getEncoding());
	}
}
