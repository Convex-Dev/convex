package convex.core.data;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

import convex.core.data.prim.CVMChar;
import convex.core.exceptions.BadFormatException;

public class Strings {
	public static final StringShort EMPTY = StringShort.EMPTY;
	
	public static final StringShort NIL = StringShort.create("nil");
	public static final StringShort TRUE = StringShort.create("true");
	public static final StringShort FALSE = StringShort.create("false");
	
	public static final StringShort BAD_SIGNATURE = StringShort.create("Bad Signature!");
	public static final StringShort BAD_FORMAT = StringShort.create("Bad Massage Format!");
	
	public static final StringShort COLON = StringShort.create(":");
	public static final StringShort HEX_PREFIX = StringShort.create("0x");

	public static final int MAX_ENCODING_LENGTH = Math.max(StringShort.MAX_ENCODING_LENGTH,StringTree.MAX_ENCODING_LENGTH);


	/**
	 * Byte value used for looking outside a String
	 * 0xff (-1) is invalid UTF-8
	 */
	public static final byte EXCESS_BYTE = -1;


	/**
	 * Reads a CVM String value from a bytebuffer. Assumes tag already read.
	 * 
	 * @param bb ByteBuffer to read from
	 * @return String instance
	 * @throws BadFormatException If format has problems
	 */
	public static AString read(ByteBuffer bb) throws BadFormatException {
		long length=Format.readVLCLong(bb);
		if (length==0) return StringShort.EMPTY;
		if (length<0) throw new BadFormatException("Negative string length!");
		if (length>Integer.MAX_VALUE) throw new BadFormatException("String length too long! "+length);
		if (length<=StringShort.MAX_LENGTH) {
			return StringShort.read(length,bb);
		}
		return StringTree.read(length,bb);
	}

	/**
	 * Create a canonical CVM String from a regular Java String
	 * @param s Java String to convert.
	 * @return CVM String instance.
	 */
	public static AString create(String s) {
		if (s.length()==0) return StringShort.EMPTY;
		CharsetEncoder encoder=getEncoder();
		ByteBuffer bb;
		try {
			bb = encoder.encode(CharBuffer.wrap(s));
		} catch (CharacterCodingException e) {
			throw new Error("Shouldn't happen!",e);
		}
		BlobBuilder builder=new BlobBuilder();
		builder.append(bb);
		return Strings.create(builder.toBlob());
	}
	
	/**
	 * Creates a string by joining a sequence of substrings with the given separator
	 * @param ss Sequence of Strings to join
	 * @param separator any String to use as a separator.
	 * @return Concatenated String, including the separator. Will return the empty string if the seqence is empty.
	 */
	public static AString join(ASequence<AString> ss,AString separator) {
		long n=ss.count();
		if (n==0) return StringShort.EMPTY;
		BlobBuilder builder=new BlobBuilder();
		builder.append(ss.get(0));
		for (long i=1; i<n; i++) {
			builder.append(separator);
			builder.append(ss.get(i));
		}
		return Strings.create(builder.toBlob());
	}
	
	/**
	 * Creates a string by joining a sequence of substrings with the given separator
	 * @param ss Sequence of Strings to join
	 * @param separator any CVM Character to use as a separator.
	 * @return Concatenated String, including the separator. Will return the empty string if the seqence is empty.
	 */
	public static AString join(ASequence<AString> ss,CVMChar separator) {
		long n=ss.count();
		if (n==0) return StringShort.EMPTY;
		BlobBuilder builder=new BlobBuilder();
		builder.append(ss.get(0));
		for (long i=1; i<n; i++) {
			builder.append(separator);
			builder.append(ss.get(i));
		}
		return Strings.create(builder.toBlob());
	}

	public static AString create(ABlob b) {
		long n=b.count();
		if (n<=StringShort.MAX_LENGTH) {
			return StringShort.create(b.toFlatBlob());
		}
		return StringTree.create(b);
	}
	
	/**
	 * Constructs a UTF-8 CVM String from raw hex digits. This does not perform checking of
	 * valid UTF: It is possible to construct bad strings this way. This is allowable on the CVM, and
	 * useful for testing.
	 * 
	 * @param hexString String containing hex digits
	 * @return New CVM String (possibly not valid UTF)
	 */
	public static AString fromHex(String hexString) {
		return create(Blobs.fromHex(hexString));
	}

	public static StringShort empty() {
		return StringShort.EMPTY;
	}

	public static CharsetDecoder getDecoder() {
		Charset charset = StandardCharsets.UTF_8;
		CharsetDecoder dec=charset.newDecoder();
		dec.onMalformedInput(CodingErrorAction.REPLACE);
		dec.onUnmappableCharacter(CodingErrorAction.REPLACE);
		// dec.replaceWith(Constants.BAD_CHARACTER_STRING);
		return dec;
	}
	
	private static CharsetEncoder getEncoder() {
		Charset charset = StandardCharsets.UTF_8;
		CharsetEncoder enc=charset.newEncoder();
		enc.onUnmappableCharacter(CodingErrorAction.REPLACE);
		enc.onMalformedInput(CodingErrorAction.REPLACE);
		// enc.replaceWith(Constants.BAD_CHARACTER_UTF);
		return enc;
	}

	public static AString appendAll(AString[] strs) {
		BlobBuilder bb=new BlobBuilder();
		for (AString s: strs) {
			bb.append(s);
		}
		return Strings.create(bb.toBlob());
	}


}
