package convex.core.crypto;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

import convex.core.Init;

public class PEMTools {
	// private static String encryptionAlgorithm="AES-128-CBC";
	
	public static String writePEM(AKeyPair kp) {

		PrivateKey priv=kp.getPrivate();
		// PublicKey pub=kp.getPublic();
		PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(
				   priv.getEncoded());
		
		byte[] encoded=keySpec.getEncoded();
		String base64=Base64.getEncoder().encodeToString(encoded);
		
		StringBuilder sb=new StringBuilder();
		sb.append("-----BEGIN PRIVATE KEY-----");
		sb.append(System.lineSeparator());
		sb.append(base64);
		sb.append(System.lineSeparator());
		sb.append("-----END PRIVATE KEY-----");
		String pem=sb.toString();
		return pem;
	}
	
	public static AKeyPair readPEM(String pem) throws GeneralSecurityException {
		String publicKeyPEM = pem.trim()
			      .replace("-----BEGIN PRIVATE KEY-----", "")
			      .replaceAll(System.lineSeparator(), "")
			      .replace("-----END PRIVATE KEY-----", "");
		
		byte[] bs = Base64.getDecoder().decode(publicKeyPEM);

		KeyFactory keyFactory = KeyFactory.getInstance("Ed25519");
		PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(bs);
		PrivateKey priv=keyFactory.generatePrivate(keySpec);
		PublicKey pub=keyFactory.generatePublic(keySpec);
		return Ed25519KeyPair.create(pub, priv);  
	}
	
	public static void main(String[] args) throws Exception {
		String pem=writePEM(Init.HERO_KP);
		System.out.println(pem);
		
		AKeyPair kp=readPEM(pem);
		System.out.println(kp);
	}
}
