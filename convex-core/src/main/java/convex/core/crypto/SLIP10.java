package convex.core.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import convex.core.data.Blob;
import convex.core.util.Utils;
import convex.core.exceptions.Panic;

/**
 * Class implementing SLIP-0010 private key generations for Ed25519 private keys
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
	 * Gets the the master key for a given seed according to SLIP10
	 * @param bip39seed BIP39 seed value (or other source of good entropy!)
	 * @return Blob containing the SLIP10 master key
	 */
	public static Blob getMaster(Blob bip39seed) {
		try {
			Mac hmac=Mac.getInstance(HMAC_ALGORITHM);
			
			hmac.init(masterKey);
			byte[] data=bip39seed.getBytes();
			hmac.update(data);
			Blob result=Blob.wrap(hmac.doFinal());
			return result;
		} catch (NoSuchAlgorithmException | InvalidKeyException e) {
			throw new Panic(e);
		}
	}
	
	/**
	 * Derives an Ed25519 private key from a BIP32 master key
	 * @param master Master key as defined in SLIP10
	 * @param ixs key derivation path indexes
	 */
	public static Blob derive(Blob master, int... ixs)  {
		if (ixs.length==0) return master;
		if (ixs.length>255) throw new IllegalArgumentException("Maximum BIP32 path length exceeded (must be 255 or less)");
		byte[] bs=master.getBytes();
		if (bs.length!=64) throw new IllegalArgumentException("Invalid SLIP10 master key, must be 64 bytes");
		byte[] data=new byte[1+32+4]; // 0x00 || ser256(kpar) || ser32(i)) from SLIP-10
			
		try {
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
			Blob result= Blob.wrap(bs);
			return result;
		} catch (GeneralSecurityException e) {
			throw new Error("Failure in SLIP-10!!!",e);
		}
	}

	public static AKeyPair deriveKeyPair(Blob bip39seed, int... ixs) {
		Blob master = getMaster(bip39seed);
		Blob keySeed = derive(master,ixs).slice(0, 32);
		AKeyPair kp=AKeyPair.create(keySeed);
		return kp;
	}
	
	
}
