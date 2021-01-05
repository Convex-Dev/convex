package convex.core.crypto;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;

import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;

import convex.core.exceptions.TODOException;
import convex.core.util.Utils;

public class PEMUtils {

	public static Writer writePEM(AKeyPair kp, String password) {
		throw new TODOException();
	}
	
	
	public static AKeyPair readPEM(Reader pem) throws IOException {
		PEMParser pp=new PEMParser(pem);
		Object o=pp.readObject();
		if (!(o instanceof PEMKeyPair)) {
			throw new IllegalArgumentException("Does not appear to contain PEM key pair, got: "+Utils.getClassName(o));
		}
		
		// PEMKeyPair pkp=(PEMKeyPair)o;
		throw new TODOException();
	}
}
