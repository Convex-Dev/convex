package convex.core.crypto;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.File;
import java.io.IOException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;

import org.junit.jupiter.api.Test;

public class PFXTest {

	@Test public void testNewStore() throws IOException, KeyStoreException, NoSuchAlgorithmException, CertificateException {
		File f=File.createTempFile("temp-keystore", "pfx");
		
		PFXUtils.createStore(f, "test");
		
		// check password is being applied
		assertThrows(IOException.class,()->PFXUtils.loadStore(f,"foobar"));
		
		// don't throw, no integrity checking on null?
		//assertThrows(IOException.class,()->PFXUtils.loadStore(f,null));
		
		KeyStore ks=PFXUtils.loadStore(f, "test");
	}
}
