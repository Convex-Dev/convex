package convex.examples;

import java.security.InvalidKeyException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;

import convex.core.data.Blob;
import convex.core.util.Utils;

/**
 * Test class for Ed25519 functionality
 */
public class Ed25519Sign {

	public static void main(String[] args) throws NoSuchAlgorithmException, NoSuchProviderException, InvalidKeyException, SignatureException {
		
		KeyPairGenerator kpg = KeyPairGenerator.getInstance("Ed25519", "BC");
	    KeyPair kp = kpg.generateKeyPair();
	    
	    {
		    byte[] enc=kp.getPrivate().getEncoded();
		    System.out.println(enc.length + " bytes in private key encoding:");
		    System.out.println(" => "+Blob.wrap(enc).toHexString());
	    }
	    
	    {
		    byte[] enc=kp.getPublic().getEncoded();
		    System.out.println(enc.length + " bytes in public key encoding:");
		    System.out.println(" => "+Blob.wrap(enc).toHexString());
	    }
	    
	    Signature sig = Signature.getInstance("Ed25519");
	    sig.initSign(kp.getPrivate());
	    
	    byte[] msg=Utils.hexToBytes("cafebabe");
	    
	    sig.update(msg);
	    byte[] sbs = sig.sign();

	    System.out.println("Sig: "+Utils.toHexString(sbs)+" ("+sbs.length+" bytes)");
	    
	    PublicKey pubKey=kp.getPublic();
	    sig.initVerify(pubKey);
	    sig.update(msg);
	    System.out.println("Verify: "+sig.verify(sbs));
	    
	}

}
