package convex.core.crypto.util;

import convex.core.data.AString;
import convex.core.data.AccountKey;
import convex.core.data.Blob;
import convex.core.data.Strings;

public class Multikey {
	
	public static AString encodePublicKey(AccountKey ak) {
		byte[] bs=new byte[32+2]; // 0xed01 + public key
		bs[0]=(byte) 0xed;
		bs[1]=(byte) 0x01;
		ak.getBytes(bs, 2);
		
		return Strings.create("z"+Base58.encode(bs));
	}
	
	public static AString encodeSecretKeey(Blob seed) {
		byte[] bs=new byte[32+2]; // 0x8026 + public key
		bs[0]=(byte) 0x80;
		bs[1]=(byte) 0x26;
		seed.getBytes(bs, 2);
		
		return Strings.create("z"+Base58.encode(bs));
	}
	
	public static Blob decodeSecretKey(AString sk) {
		return decodeSecretKey(sk.toString());
	}
	
	public static Blob decodeSecretKey(String sk) {
		if (sk.charAt(0)!='z') throw new IllegalArgumentException("Expected 'z' at start of multibase key");
		
		byte[] bs=Base58.decode(sk.substring(1)); // decode, skipping leading z
		if (bs.length!=(32+2)) throw new IllegalArgumentException("Invalid Ed25519 secret key encoding length:" +bs.length);
		if ((bs[0]!=(byte) 0x80||(bs[1]!=(byte) 0x26))) {
			throw new IllegalArgumentException("Invalid Ed25519 secret key header");
		}
		return Blob.wrap(bs, 2, 32);
	}

	public static AccountKey decodePublicKey(String pk) {
		if (pk.charAt(0)!='z') throw new IllegalArgumentException("Expected 'z' at start of multibase key");
		
		byte[] bs=Base58.decode(pk.substring(1)); // decode, skipping leading z
		if (bs.length!=(32+2)) throw new IllegalArgumentException("Invalid Ed25519 public key encoding length:" +bs.length);
		if ((bs[0]!=(byte) 0xed||(bs[1]!=(byte) 0x01))) {
			throw new IllegalArgumentException("Invalid Ed25519 public key header");
		}
		return AccountKey.wrap(bs, 2);

	}

}
