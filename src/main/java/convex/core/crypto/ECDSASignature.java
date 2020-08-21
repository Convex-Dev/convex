package convex.core.crypto;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.security.Security;
import java.util.Arrays;

import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.asn1.x9.X9IntegerConverter;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.ec.CustomNamedCurves;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.signers.ECDSASigner;
import org.bouncycastle.crypto.signers.HMacDSAKCalculator;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.math.ec.ECAlgorithms;
import org.bouncycastle.math.ec.ECCurve;
import org.bouncycastle.math.ec.ECPoint;
import org.bouncycastle.math.ec.FixedPointCombMultiplier;
import org.bouncycastle.math.ec.custom.sec.SecP256K1Curve;

import convex.core.data.ABlob;
import convex.core.data.Address;
import convex.core.data.Blob;
import convex.core.data.Tag;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.exceptions.TODOException;
import convex.core.util.Utils;

/**
 * Signature according to ECDSA standard, the signature is the triplet
 * (recoveryID, r, s)
 * 
 * We store the recovery ID recID as per bitcoinj implementation with values 0-3
 * and serialise by adding 27
 * 
 * which enables the public key of the signer to be recovered from the Signature
 * 
 * "The whole problem with the world is that fools and fanatics are always so
 * certain of themselves, but wiser people so full of doubts." 
 * - Bertrand Russell
 * 
 */
public final class ECDSASignature extends ASignature {

	public static final String CURVE_SPEC = "secp256k1";
	public static final String SIGN_ALGORITHM = "SHA256withECDSA";

	static final X9ECParameters SECP256K1_EC_PARAMS = CustomNamedCurves.getByName("secp256k1");
	static final ECCurve SECP256K1_CURVE = SECP256K1_EC_PARAMS.getCurve();

	public static final BigInteger N = SECP256K1_EC_PARAMS.getN();
	public static final int N_BITLENGTH = N.bitLength();
	public static final BigInteger HALF_N = N.shiftRight(1);

	// secp256k1 domain parameters
	static final ECDomainParameters SECP256K1_PARAMS = new ECDomainParameters(SECP256K1_CURVE,
			SECP256K1_EC_PARAMS.getG(), SECP256K1_EC_PARAMS.getN(), SECP256K1_EC_PARAMS.getH());
	// We use secp256k1 curve, as per Ethereum standard

	// Instantiate Bouncy Castle provider
	public static final BouncyCastleProvider SECURITY_PROVIDER = new org.bouncycastle.jce.provider.BouncyCastleProvider();

	private static final int SIGNATURE_LENGTH = 65; // recID + 32 bytes for each of (r,s)

	public final int recoveryID;
	public final BigInteger r;
	public final BigInteger s;

	private ECDSASignature(int recID, BigInteger r, BigInteger s) {
		this.recoveryID = recID;
		this.r = r;
		this.s = s;
		if (!this.isCanonical()) throw new IllegalArgumentException("Attempting to construct non-canonical signature!");
	}

	/**
	 * Creates an ECDSA signature with the given pair (r,s) of 256-bit integers
	 * 
	 * @return A new Signature instance
	 */
	public static ECDSASignature create(int recID, BigInteger r, BigInteger s) {
		return new ECDSASignature(recID, r, s);
	}

	// generic hashcode and equals methods

	@Override
	public int hashCode() {
		// simple hashcode calculation. Not cryptographically secure!
		return 31 * r.hashCode() + s.hashCode() + 1337 * recoveryID;
	}

	@Override
	public boolean equals(Object o) {
		if (o instanceof ECDSASignature) {
			return equals((ECDSASignature) o);
		}
		return false;
	}

	public boolean equals(ECDSASignature other) {
		if (!(recoveryID == other.recoveryID)) return false;
		if (!r.equals(other.r)) return false;
		return s.equals(other.s);
	}

	/**
	 * Checks if the s value is canonical (i.e. the lower of s and (-s mod N))
	 * non-canonical signatures are not considered valid
	 * 
	 * We need this to avoid transaction malleability attacks as per e.g. <ref>
	 * 
	 * @return true if the signature is canonical, false otherwise
	 */
	@Override
	public boolean isCanonical() {
		return s.compareTo(ECDSASignature.HALF_N) <= 0;
	}

	@Override
	protected Blob createEncoding() {
		byte[] bs = new byte[SIGNATURE_LENGTH];
		assert ((recoveryID >= 0) && (recoveryID <= 3));
		bs[0] = (byte) (recoveryID + 27);
		Utils.writeUInt(r, bs, 1, 32);
		Utils.writeUInt(s, bs, 33, 32);
		return Blob.wrap(bs);
	}

	@Override
	public ByteBuffer write(ByteBuffer b) {
		b = b.put(Tag.SIGNATURE);
		return writeRaw(b);
	}

	@Override
	public ByteBuffer writeRaw(ByteBuffer b) {
		b = b.put((byte) (recoveryID + 27));
		b = Utils.writeUInt256(b, r);
		b = Utils.writeUInt256(b, s);
		return b;
	}

	public static ECDSASignature read(ByteBuffer b) throws BadFormatException {
		byte recID = (byte) (b.get() - 27);
		if ((recID < 0) || (recID > 3)) {
			throw new BadFormatException("Invalid recovery ID: " + recID);
		}
		BigInteger r = Utils.readUInt256(b);
		BigInteger s = Utils.readUInt256(b);
		return create(recID, r, s);
	}

	@Override
	public int estimatedEncodingSize() {
		// allow for full length of signature plus message tag
		return SIGNATURE_LENGTH + 1;
	}

	@Override
	public void ednString(StringBuilder sb) {
		sb.append("#sig {");
		sb.append(":recID " + Utils.ednString(recoveryID));
		sb.append(',');
		sb.append(":r " + Utils.ednString(r));
		sb.append(',');
		sb.append(":s " + Utils.ednString(s));
		sb.append('}');
	}

	@Override
	public void validateCell() throws InvalidDataException {
		if (r.signum() < 0) throw new InvalidDataException("Negative r!", this);
		if (s.signum() < 0) throw new InvalidDataException("Negative s!", this);
		if ((recoveryID < 0) || (recoveryID > 3))
			throw new InvalidDataException("Invalid recovery ID: " + recoveryID, this);
	}

	@Override
	public boolean verify(Hash hash, Address address) {
		BigInteger recoveredKey = ECDSASignature.publicKeyFromSignature(this, hash);
		Address recoveredAddress = Address.fromPublicKey(recoveredKey);
		return address.equals(recoveredAddress);
	}


	/**
	 * Convert a BigInteger to an ECPoint using point compression
	 * 
	 * @param x
	 * @param yBit
	 * @return
	 */
	static ECPoint bigIntToECPoint(BigInteger x, boolean yBit) {
		X9IntegerConverter x9 = new X9IntegerConverter();
		if (x.signum() < 0) throw new Error("Unexpected negaitive x size: " + x);

		int fieldSizeBytes = x9.getByteLength(SECP256K1_CURVE);
		if (fieldSizeBytes != 32) throw new Error("Unexpected field size: " + fieldSizeBytes);
		// convert to bytes, with a extra byte for the ASN header reflecting y sign bit
		byte[] encoded = x9.integerToBytes(x, 1 + fieldSizeBytes);
		encoded[0] = (byte) (yBit ? 0x03 : 0x02);

		return SECP256K1_CURVE.decodePoint(encoded);
	}

	/**
	 * Recovers a public key from a (r,s) signature pair using the given recovery ID
	 * 
	 * @param recoveryID
	 * @param r
	 * @param s
	 * @param message
	 * @return The recovered public key.
	 */
	public static BigInteger publicKeyFromSignature(int recoveryID, BigInteger r, BigInteger s, byte[] message) {
		// implementation informed by bitcoinj / web3j
		// TODO: recheck logic vs. web3j / bitcoinj
		if ((recoveryID < 0) || (recoveryID > 3)) {
			throw new IllegalArgumentException("Invalid recovery ID: " + recoveryID);
		}
		;
		BigInteger n = SECP256K1_EC_PARAMS.getN();

		// compute x using bit 1 of recoveryID as j in x = r + j.n
		// we save a multiply and potentially an addition by noticing j is 0 or 1 only.
		BigInteger x = ((recoveryID & 2) == 0) ? r : r.add(n);

		// impossible solution since working mod q
		if (x.compareTo(SecP256K1Curve.q) >= 0) return null;

		// get the R point using bit 0 of recoveryID as y-bit for compression
		ECPoint R;
		try {
			R = bigIntToECPoint(x, (recoveryID & 1) == 1);
		} catch (Throwable t) {
			throw new IllegalArgumentException(
					"Error converting BigInteger " + x + " to ECPoint with recoveryId=" + recoveryID, t);
		}

		// bail out if we hit infinity and retry with next recovery ID
		if (!R.multiply(n).isInfinity()) return null;

		// convert message into positive BigInteger
		BigInteger e = new BigInteger(1, message);

		BigInteger eInv = e.negate().mod(n);
		BigInteger rInv = r.modInverse(n);
		BigInteger srInv = rInv.multiply(s).mod(n);
		BigInteger eInvTimesrInv = rInv.multiply(eInv).mod(n);

		ECPoint G = SECP256K1_EC_PARAMS.getG();
		ECPoint Q = ECAlgorithms.sumOfTwoMultiplies(G, eInvTimesrInv, R, srInv);

		// convert q to BigInteger, skipping first byte of encoding
		byte[] qBytes = Q.getEncoded(false);
		return new BigInteger(1, Arrays.copyOfRange(qBytes, 1, qBytes.length));
	}

	/**
	 * Recovers a public key from a signature
	 */
	public static BigInteger publicKeyFromSignature(ECDSASignature sig, Hash messageHash) {
		return ECDSASignature.publicKeyFromSignature(sig.recoveryID, sig.r, sig.s, messageHash.getBytes());
	}

	/**
	 * Recovers a public key from a signature
	 */
	public static BigInteger publicKeyFromSignature(ECDSASignature sig, byte[] message) {
		return ECDSASignature.publicKeyFromSignature(sig.recoveryID, sig.r, sig.s, message);
	}

	/**
	 * Computes a recovery ID which allows the public key to be extracted from the
	 * signature uniquely As per bitcoinj approach we use recIDs of 0,1,2,3
	 * 
	 * @param r
	 * @param s
	 * @param message
	 * @return
	 */
	static int computeRecoveryID(BigInteger r, BigInteger s, byte[] message, BigInteger publicKey) {
		// try all four possible combinations until we find one that returns the
		// required
		// public key. This should always happen if the arguments are valid?
		for (int i = 0; i < 4; i++) {
			BigInteger recoveredKey = ECDSASignature.publicKeyFromSignature(i, r, s, message);
			if ((recoveredKey != null) && (recoveredKey.equals(publicKey))) {
				return i;
			}
		}
		throw new IllegalArgumentException("Unable to compute a valid recovery ID!");
	}

	public static ECDSASignature sign(ABlob data, AKeyPair keyPair) {
		byte[] bytes = data.getBytes();
		return sign(bytes, keyPair);
	}

	/**
	 * Sign a message (which should ideally be a hash of larger data) with the
	 * private key of this key pair.
	 * 
	 * @return The new Signature
	 */
	public static ECDSASignature sign(byte[] message, AKeyPair kp) {
		ECDSAKeyPair keyPair=(ECDSAKeyPair) kp;
		
		ECDSASigner ecdsaSigner = new ECDSASigner(new HMacDSAKCalculator(new SHA256Digest()));
		ECPrivateKeyParameters privKeyParams = new ECPrivateKeyParameters(keyPair.getPrivateKey(), SECP256K1_PARAMS);
		ecdsaSigner.init(true, privKeyParams);

		BigInteger[] rsPair = ecdsaSigner.generateSignature(message);
		BigInteger r = rsPair[0];
		assert (r.signum() > 0);
		BigInteger s = rsPair[1];
		assert (s.signum() > 0);

		// ensure signature is canonical, in lower half of curve order
		// this prevents potential transaction malleability / replay attacks
		if (s.compareTo(HALF_N) >= 0) {
			s = N.subtract(s);
		}

		BigInteger publicKey = keyPair.getPublicKey();
		int recID = computeRecoveryID(r, s, message, publicKey);
		assert ((recID >= 0) && (recID <= 3));

		ECDSASignature signature = create(recID, r, s);

		// assert(verify(message, signature));
		return signature;
	}

	/**
	 * Computes an uncompressed public key (x,y) from the given private key.
	 * 
	 * @return Positive BigInteger representing the public key
	 */
	public static BigInteger publicKeyFromPrivate(BigInteger privKey) {
		// Use Q = dG multiplication to compute the public key
		ECPoint ecp = new FixedPointCombMultiplier().multiply(SECP256K1_PARAMS.getG(), privKey);

		// get the encoded public key
		byte[] encoded = ecp.getEncoded(false);

		// return a BigInteger excluding the prefix byte (0x04)
		assert (encoded[0] == 4);
		return new BigInteger(1, Arrays.copyOfRange(encoded, 1, encoded.length));
	}

	/**
	 * Verify a signed message message using a given signature and Public Key.
	 * 
	 * @returns true if the signature is valid, false otherwise
	 */
	public static boolean verify(Hash messageHash, ECDSASignature signature, BigInteger publicKey) {
		if (!signature.isCanonical()) return false; // don't allow non-canonical signatures to be valid
		BigInteger recoveredKey = ECDSASignature.publicKeyFromSignature(signature, messageHash);
		return publicKey.equals(recoveredKey);
	}

	/**
	 * Verify a signed message using a given signature and public key.
	 * 
	 * @returns true if the signature is valid, false otherwise
	 */
	public static boolean verify(byte[] message, ECDSASignature signature, BigInteger publicKey) {
		if (!signature.isCanonical()) return false; // don't allow non-canonical signtaures to be valid
		BigInteger recoveredKey = ECDSASignature.publicKeyFromSignature(signature, message);
		return publicKey.equals(recoveredKey);
	}
	
	static {
		Security.addProvider(ECDSASignature.SECURITY_PROVIDER);
	}
	
	/**
	 * Static method to force initialisation of signing class, including setting up
	 * the security provider.
	 */
	public static void init() {
		// Nothing needed
	}

	@Override
	public int getRefCount() {
		return 0;
	}

	@Override
	public String toHexString() {
		throw new TODOException();
	}


}