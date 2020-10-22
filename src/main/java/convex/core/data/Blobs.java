package convex.core.data;

import java.nio.ByteBuffer;
import java.util.Random;

import convex.core.exceptions.BadFormatException;
import convex.core.util.Utils;

public class Blobs {

	static final int CHUNK_SHIFT = 12;
	
	public static final int MAX_ENCODING_LENGTH = Math.max(Blob.MAX_ENCODING_LENGTH, BlobTree.MAX_ENCODING_LENGTH);

	public static <T extends ABlob> T createRandom(long length) {
		return createRandom(new Random(),length);
	}
	
	@SuppressWarnings("unchecked")
	public static <T extends ABlob> T createRandom(Random r, long length) {
		if (length <= Blob.CHUNK_LENGTH) return (T) Blob.createRandom(r, length);

		int numChunks = Utils.checkedInt(((length - 1) >> CHUNK_SHIFT) + 1);

		Blob[] blobs = new Blob[numChunks];
		for (int i = 0; i < numChunks; i++) {
			Blob b = Blob.createRandom(r, Math.min(Blob.CHUNK_LENGTH, length - i * Blob.CHUNK_LENGTH));
			blobs[i] = b;
		}
		return (T) BlobTree.create(blobs);
	}

	public static ABlob canonical(ABlob a) {
		long length = a.length();
		if (length <= Blob.CHUNK_LENGTH) return a.toBlob();
		return BlobTree.create(a);
	}

	public static ABlob fromHex(String a) {
		long slength = a.length();
		if ((slength & 1) != 0) throw new IllegalArgumentException("byte hex string must have even length");
		long length = slength / 2;
		Blob fullBlob = Blob.fromHex(a);
		if (length <= Blob.CHUNK_LENGTH) return fullBlob;
		return BlobTree.create(fullBlob);
	}

	/**
	 * Reads a Blob from a ByteBuffer.
	 * 
	 * @param bb ByteBuffer starting with a blob encoding
	 * @return Blob read from ByteBuffer
	 * @throws BadFormatException
	 */
	public static ABlob read(ByteBuffer bb) throws BadFormatException {
		long len = Format.readVLCLong(bb);
		if (len < 0L) throw new BadFormatException("Negative blob length?");
		if (len > Blob.CHUNK_LENGTH) return BlobTree.read(bb, len);
		byte[] buff = new byte[Utils.checkedInt(len)];
		bb.get(buff);
		return Blob.wrap(buff);
		// TODO keep byte format representation?
	}

	@SuppressWarnings("unchecked")
	public static <T extends ABlob> T readFromBlob(Blob source) throws BadFormatException {
		int sLen = source.length;
		if (sLen < 2) throw new BadFormatException("Trying to read Blob from insufficient source of size " + sLen);
		// read length at position 1 (skipping tag)
		long len = Format.readVLCLong(source.store, source.offset + 1);

		T result = null;
		if (len < 0L) throw new BadFormatException("Negative blob length?");
		if (len > Blob.CHUNK_LENGTH) {
			result = (T) BlobTree.read(source, len);
		} else {
			result = (T) Blob.read(source, len);
		}
		// we can attach original blob as source at this point
		result.attachEncoding(source);
		return result;
	}


}
