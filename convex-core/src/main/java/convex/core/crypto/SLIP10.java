package convex.core.crypto;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import convex.core.data.Blob;
import convex.core.util.Utils;

/**
 * Class implementing SLIP-0010 private key generations
 * 
 * See: https://github.com/satoshilabs/slips/blob/master/slip-0010.md
 */
public class SLIP10 {
	
	// Algorithm identifier for HMAC-SHA512 (as specified in RFC 4231)
	private static final String HMAC_ALGORITHM= "HmacSHA512";

	// Key as specified in SLIP10 for Ed25519
	private static final byte[] ED25519_KEY = "ed25519 seed".getBytes(StandardCharsets.UTF_8);
	
	private static final SecretKeySpec masterKey=new SecretKeySpec(ED25519_KEY,HMAC_ALGORITHM);

	/**
	 * Gets the SLIP-10 HMAC as used in the master key
	 * @param bipSeed Bip39 seed value (or other source of good entropy!)
	 * @return Blob containing the master key
	 * @throws NoSuchAlgorithmException
	 * @throws InvalidKeyException
	 */
	public static Blob hmac(Blob bipSeed) throws NoSuchAlgorithmException, InvalidKeyException {
		Mac hmac=Mac.getInstance(HMAC_ALGORITHM);
		
		hmac.init(masterKey);
		byte[] data=bipSeed.getBytes();
		hmac.update(data);
		Blob result=Blob.wrap(hmac.doFinal());
		return result;
	}
	
	public static Blob deriveKey(Blob master, int... ixs) throws NoSuchAlgorithmException {
		try {
			byte[] bs=master.getBytes();
			byte[] data=new byte[1+32+4]; // 0x00 || ser256(kpar) || ser32(i)) from SLIP-10
			
			Mac hmac=Mac.getInstance(HMAC_ALGORITHM);
			
			for (int i=0; i<ixs.length; i++) {
				SecretKeySpec key=new SecretKeySpec(bs,32,32,HMAC_ALGORITHM); // key is cpar
				hmac.init(key);
				
				System.arraycopy(bs, 0, data, 1, 32); // kpar
				Utils.writeInt(data, 1+32, ixs[i]|0x80000000); // ser32(i);
				hmac.update(data);
				hmac.doFinal(bs,0); // get output, note we need the destination index
			}
			
			// Wrap the bytes of the newly derived seed to get the derived Ed25519 key
			Blob result= Blob.create(bs,0,AKeyPair.SEED_LENGTH);
			Arrays.fill(bs, (byte) 0);
			return result;
		} catch (Exception e) {
			throw new Error("Failure in SLIP-10!!!",e);
		}
	}
	
	
}
