package convex.core.data;

import java.nio.ByteBuffer;
import java.util.Random;

import org.bouncycastle.util.Arrays;

import convex.core.exceptions.BadFormatException;
import convex.core.lang.RT;
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

		BlobBuilder bb=new BlobBuilder();
		while (length>Blob.CHUNK_LENGTH) {
			bb.append(createRandom(r,Blob.CHUNK_LENGTH));
			length -=Blob.CHUNK_LENGTH;
		}
		bb.append(createRandom(r,length));
		return (T)bb.toBlob();
	}

	/**
	 * Converts any blob to a the correct canonical Blob format
	 * @param a Any Blob
	 * @return Canonical version s a Blob or BlobTree
	 */
	public static ABlob toCanonical(ABlob a) {
		long length = a.count();
		if (length <= Blob.CHUNK_LENGTH) return a.toFlatBlob();
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
	 * Best effort attempt to parse a Blob. Must parse as a Blob of correct length
	 * @param o Object expected to contain a Blob value
	 * @return ABlob value, or null if not parseable
	 */
	public static ABlob parse(Object o) {
		if (o instanceof ABlob) {
			ABlob b= (ABlob)o;
			if (b.isRegularBlob()) return b;
			return null;
		}
		if (o instanceof ACell) o=RT.jvm((ACell)o);
		if (!(o instanceof String)) return null;;
		String s=(String)o;
		return parse(s);
	}
	
	/**
	 * Best effort attempt to parse a Blob. Must parse as a Blob of correct length
	 * @param s String expected to contain a HasBlobh value
	 * @return ABlob value, or null if not parseable
	 */
	public static ABlob parse(String s) {
		if (s==null) return null;
		s=s.trim();
		if (s.startsWith("0x")) s=s.substring(2);
		return fromHex(s);
	}

	/**
	 * Reads a Blob from an Encoding in a ByteBuffer.
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

	/**
	 * Reads a canonical Blob from a byte source
	 * @param <T> Type of Blob result
	 * @param source Source blob, containing tag
	 * @return Canonical Blob
	 * @throws BadFormatException if the Blob encoding format is invalid
	 */
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

	public static Blob empty() {
		return Blob.EMPTY;
	}

	/** 
	 * Gets a zero-based array containing the contents of the given Blob.
	 * MAY use current internal array if possible.
	 * 
	 * @param b Blob to get array for
	 * @return byte array containing the blob contents starting at offset zero
	 */
	public static byte[] zeroBasedArray(AArrayBlob b) {
		if (b.getInternalOffset()==0) {
			return b.getInternalArray();
		} else {
			return b.getBytes();
		}
	}
}
