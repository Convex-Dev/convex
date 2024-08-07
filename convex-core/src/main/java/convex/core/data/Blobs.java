package convex.core.data;

import java.util.Random;

import org.bouncycastle.util.Arrays;

import convex.core.data.impl.ZeroBlob;
import convex.core.data.util.BlobBuilder;
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
	 * Converts any blob to a correct canonical Blob format
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
		if (fullBlob==null) return null;
		
		long length = slength / 2;
		if (length <= Blob.CHUNK_LENGTH) return fullBlob;
		return BlobTree.create(fullBlob);
	}
	
	/**
	 * Creates a blob from a hex string
	 * @param a Hex String
	 * @return Blob created, or null if String not valid hex
	 */
	public static ABlob fromHex(AString a) {
		return fromHex(a.toString());
	}
	
	/**
	 * Best effort attempt to parse a Blob. Must parse as a Blob of correct length
	 * @param o Object expected to contain a Blob value
	 * @return ABlob value, or null if not parseable
	 */
	public static ABlob parse(Object o) {
		if (o instanceof ABlob) {
			return (ABlob)o;
		}
		if (o instanceof ACell) o=RT.jvm((ACell)o);
		if (!(o instanceof String)) return null;;
		String s=(String)o;
		return parse(s);
	}
	
	/**
	 * Best effort attempt to parse a Blob. Must parse as a Blob of correct length.
	 * Leading "0x" optional.
	 * @param s String expected to contain a single Blob value in hex
	 * @return ABlob value, or null if not parseable
	 */
	public static ABlob parse(String s) {
		if (s==null) return null;
		s=s.trim();
		if (s.startsWith("0x")) s=s.substring(2);
		return fromHex(s);
	}

	/**
	 * Reads a canonical Blob from a Blob source
	 * @param <T> Type of Blob result
	 * @param source Source blob, containing tag
	 * @param pos position to read from source, assumed to be tag
	 * @return Canonical Blob
	 * @throws BadFormatException if the Blob encoding format is invalid
	 */
	@SuppressWarnings("unchecked")
	public static <T extends ABlob> T read(Blob source, int pos) throws BadFormatException {
		int sLen = source.size()-pos;
		if (sLen < 2) throw new BadFormatException("Trying to read Blob from insufficient source of size " + sLen);
		// read length at position 1 (skipping tag)
		long count = Format.readVLCCount(source.store, source.offset + pos+ 1); // skip pos and tag

		T result = null;
		if (count < 0L) throw new BadFormatException("Negative blob length?");
		if (count > Blob.CHUNK_LENGTH) {
			result = (T) BlobTree.read(count,source, pos);
		} else {
			result = (T) Blob.read(source,pos, count);
		}
		// encoding should be attached from Blob reads
		return result;
	}

	/**
	 * Create a Blob entirely filled with a given value
	 * @param value Byte value to fill with (low 8 bits used)
	 * @param length Length of Blob to create
	 * @return Blob filled with given value
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
	
	/**
	 * Create a Blob entirely filled with zeros
	 * @param length Length of Blob to create
	 * @return Blob filled with zeros
	 */
	public static ABlob createZero(long length) {
		if (length<=Blob.CHUNK_LENGTH) return Blob.EMPTY_CHUNK.slice(0,length);
		return ZeroBlob.create(length);
	}

	public static Blob empty() {
		return Blob.EMPTY;
	}

	/** 
	 * Gets a zero-based array containing the contents of the given Blob.
	 * MAY use current internal array if possible.
	 * WARNING: may return underlying array, should never be mutated
	 * 
	 * @param b Blob to get array for
	 * @return byte array containing the blob contents starting at offset zero
	 */
	public static byte[] ensureZeroBasedArray(AArrayBlob b) {
		if (b.getInternalOffset()==0) {
			return b.getInternalArray();
		} else {
			return b.getBytes();
		}
	}

	/**
	 * Create a 8-byte Blob representing a long value
	  */
	public static Blob forLong(long c) {
		byte[] bs=new byte[8];
		Utils.writeLong(bs, 0, c);
		return Blob.wrap(bs);
	}

}
