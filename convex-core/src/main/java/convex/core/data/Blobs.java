package convex.core.data;

import java.nio.ByteBuffer;
import java.util.Random;

import org.bouncycastle.util.Arrays;

import convex.core.exceptions.BadFormatException;
import convex.core.util.Utils;

public class Blobs {

	static final int CHUNK_SHIFT = 12;
	
	public static final int MAX_ENCODING_LENGTH = Math.max(Blob.MAX_ENCODING_LENGTH, BlobTree.MAX_ENCODING_SIZE);

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

	/**
	 * Converts any blob to a the correct canonical Blob format
	 * @param a Any Blob
	 * @return Canonical version s a Blob or BlobTree
	 */
	public static ABlob toCanonical(ABlob a) {
		long length = a.count();
		if (length <= Blob.CHUNK_LENGTH) return a.toBlob();
		return BlobTree.create(a);
	}

	/**
	 * Creates a blob from a hex string
	 * @param a Hex String
	 * @return Blob created, or null if String not valid hex
	 */
	public static ABlob fromHex(String a) {
		long slength = a.length();
		if ((slength & 1) != 0) return null;
		Blob fullBlob = Blob.fromHex(a);
		
		long length = slength / 2;
		if (length <= Blob.CHUNK_LENGTH) return fullBlob;
		return BlobTree.create(fullBlob);
	}

	/**
	 * Reads a Blob from a ByteBuffer.
	 * 
	 * @param bb ByteBuffer starting with a blob encoding
	 * @return Blob read from ByteBuffer
	 * @throws BadFormatException If format is invalid
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

	/**
	 * Create a Blob entirely filled with a given value
	 * @param value Byte value to fill with (low 8 bits used)
	 * @param length Length of Blob to create
	 * @return BlobTree filled with given value
	 */
	public static ABlob createFilled(int value, long length) {
		byte fillByte=(byte)value;
		if (length<=Blob.CHUNK_LENGTH) {
			byte[] bs=new byte[Utils.checkedInt(length)];
			Arrays.fill(bs, fillByte);
			return Blob.wrap(bs);
		}
		
		int n=BlobTree.childCount(length);
		long subSize=BlobTree.childSize(length);
		ABlob fullChild=Blobs.createFilled(fillByte,subSize);
		
		ABlob[] children=new ABlob[n];
		for (int i=0; i<n-1;i++) children[i]=fullChild;
		long lastSize=length-((n-1)*subSize);
		children[n-1]=lastSize==subSize?fullChild:Blobs.createFilled(fillByte, lastSize);
		return BlobTree.createWithChildren(children);
	}


}
