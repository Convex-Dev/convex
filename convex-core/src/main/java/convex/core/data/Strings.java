package convex.core.data;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;

import convex.core.Constants;
import convex.core.data.impl.StringStore;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMChar;
import convex.core.data.util.BlobBuilder;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.Panic;
import convex.core.lang.RT;
import convex.core.util.Utils;

public class Strings {
	public static final StringShort EMPTY = StringShort.EMPTY;
	
	public static final Ref<StringShort> EMPTY_REF = EMPTY.getRef();
	
	public static final StringShort NIL = StringShort.intern("nil");
	public static final StringShort TRUE = StringShort.intern(CVMBool.TRUE_STRING);
	public static final StringShort FALSE = StringShort.intern(CVMBool.FALSE_STRING);
	
	public static final StringShort SPACE = StringShort.intern(" ");
	public static final StringShort COLON = StringShort.intern(":");
	public static final StringShort HEX_PREFIX = StringShort.intern("0x");

	
	public static final StringShort BAD_SIGNATURE = StringShort.create("Bad Signature!");
	public static final StringShort BAD_FORMAT = StringShort.create("Bad Message Format!");
	public static final StringShort SERVER_LOADED = StringShort.create("Trx overload");
	

	public static final int MAX_ENCODING_LENGTH = Math.max(StringShort.MAX_ENCODING_LENGTH,StringTree.MAX_ENCODING_LENGTH);


	/**
	 * Byte value used for looking outside a String
	 * 0xff (-1) is invalid UTF-8
	 */
	public static final byte EXCESS_BYTE = -1;

	public static final StringShort MISSING_PEER = StringShort.create("Missing Peer!");

	public static final StringShort INSUFFICIENT_STAKE = StringShort.create("Insufficient Stake!");
	
	public static final StringShort ILLEGAL_BLOCK_SIZE = StringShort.create("Illegal Block Size!");

	public static final StringShort BACKDATED_BLOCK = StringShort.create("Block too old");

	public static final StringShort MISORDERED_BLOCK = StringShort.create("Block out of order");

	public static final StringShort NO_SUCH_ACCOUNT = StringShort.create("Account does not exist");

	public static final StringShort NO_TX_FOR_ACTOR = StringShort.create("Cannot run external transaction for actor account");

	public static final StringShort WRONG_KEY = StringShort.create("Wrong key for account");

	public static final StringShort OLD_SEQUENCE = StringShort.create("Old sequence number");

	public static final StringShort PRINT_EXCEEDED = StringShort.create(Constants.PRINT_EXCEEDED_STRING);

	public static final StringShort LONG_MIN_VALUE = StringShort.create("-9223372036854775808");

	public static final StringShort SENT = StringShort.create("Sent");

	public static final StringShort FULL_BUFFER = StringShort.create("Buffer full");

	public static final StringShort NULL = StringShort.intern("null");

	public static final StringShort TX = StringShort.intern("tx");
	
	public static final StringShort DATA = StringShort.intern("data");

	public static final StringShort HASH = StringShort.intern("hash");
	
	public static final StringShort ADDRESS = StringShort.intern("address");

	public static final StringShort AMOUNT = StringShort.intern("amount");
	
	public static final StringShort CAD3 = StringShort.intern("cad3");

	public static final StringShort SYMBOL = StringShort.intern("symbol");

	public static final StringShort SOURCE = StringShort.intern("source");

	
	public static final Comparator<AString> lengthComparator = (a,b)->{
		return Long.signum(a.count()-b.count());
	};


	public static AString wrap(byte[] utfBytes) {
		if (utfBytes.length<=StringShort.MAX_LENGTH) {
			return StringShort.wrap(utfBytes);
		} else {
			return StringTree.create(Blob.wrap(utfBytes));
		}
	}



	/**
	 * Create a canonical CVM String from a regular Java String
	 * @param s Java String to convert.
	 * @return CVM String instance, or null if input was null
	 */
	public static AString create(String s) {
		if (s==null) return null;
		int n=s.length();
		if (n==0) return StringShort.EMPTY;
		ABlob utfBlob=null;
		
		// Fast path for short pure ASCII Strings
		if (n<=Constants.MAX_NAME_LENGTH) {
			utfBlob=tryGetASCII(s);
		}
		
		if (utfBlob==null) {
			CharsetEncoder encoder=getEncoder();
			ByteBuffer bb;
			try {
				bb = encoder.encode(CharBuffer.wrap(s));
			} catch (CharacterCodingException e) {
				throw new Panic("Shouldn't happen!",e);
			}
			BlobBuilder builder=new BlobBuilder();
			builder.append(bb);
			utfBlob=builder.toBlob();
		}
		return Strings.create(utfBlob);
	}
	
	/**
	 * Create a canonical CVM String from an object
	 * @param o Java Object to convert.
	 * @return CVM String instance, or "nil" if input was null
	 */
	public static AString create(Object o) {
		if (o==null) return NIL;
		if (o instanceof ACell) {
			return RT.str((ACell) o);
		}
		return create(o.toString());
	}

	public static StringShort intern(AString value) {
		return StringStore.intern(value);
	}
	
	@SuppressWarnings("unchecked")
	public static <T extends AString> T intern(String value) {
		return (T) StringStore.intern(value);
	}
	
	public static AString create(CVMChar c) {
		return create(c.toUTFBlob());
	}
	
	private static Blob tryGetASCII(String s) {
		int n=s.length();
		byte[] bs=new byte[n];
		for (int i=0; i<n; i++) {
			char c=s.charAt(i);
			if (c>=128) return null; // non-ASCII
			bs[i]=(byte)c;
		}
		return Blob.wrap(bs);
	}

	/**
	 * Creates a string by joining a sequence of substrings with the given separator
	 * @param ss Sequence of Strings to join
	 * @param separator any String to use as a separator.
	 * @return Concatenated String, including the separator. Will return the empty string if the sequence is empty.
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
	 * @param separator any String to use as a separator.
	 * @return Concatenated String, including the separator. Will return the empty string if the array is empty.
	 */
	public static <T> AString join(T[] ss,Object separator) {
		int n=ss.length;
		if (n==0) return StringShort.EMPTY;
		BlobBuilder builder=new BlobBuilder();
		AString sep=Strings.create(separator);
		builder.append(create(ss[0]));
		for (int i=1; i<n; i++) {
			builder.append(sep);
			builder.append(create(ss[i]));
		}
		return Strings.create(builder.toBlob());
	}
	
	/**
	 * Creates a String by joining a sequence of substrings with the given separator
	 * @param ss Sequence of Strings to join
	 * @param separator any CVM Character to use as a separator.
	 * @return Concatenated String, including the separator. Will return the empty string if the sequence is empty.
	 */
	public static AString join(ASequence<AString> ss,CVMChar separator) {
		long n=ss.count();
		if (n==0) return StringShort.EMPTY;
		BlobBuilder builder=new BlobBuilder();
		for (long i=0; i<n; i++) {
			if (i!=0) builder.append(separator);
			ACell c=ss.get(i); // be defensive in case not a string
			if (c instanceof AString) {
				builder.append((AString)c);
			} else {
				return null;
			}
		}
		return Strings.create(builder.toBlob());
	}

	/**
	 * Create a string using the UTF-8 bytes in a Blob
	 * @param b Blob of UTF-8 bytes (assumed valid - invalid UTF-8 will create an invalid string)
	 * @return AString instance
	 */
	public static AString create(ABlob b) {
		long n=b.count();
		if (n<=StringShort.MAX_LENGTH) {
			return StringShort.create(b.toFlatBlob());
		}
		return StringTree.create(b);
	}
	
	/**
	 * Create a string repeating the specified character
	 * @param c Java character (need not be ASCII, but must not be a surrogate)
	 * @param count Number of times to repeat
	 * @return String instance
	 */
	public static AString createRepeated(char c, int count) {
		if (count==0) return EMPTY;
		if (Utils.isASCIIChar(c)) {
			return create(Blobs.createFilled(c, count));
		} else {
			BlobBuilder bb=new BlobBuilder();
			for (int i=0; i<count; i++) {
				bb.append(c);
			}
			return bb.getCVMString();
		}
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

	/**
	 * Get an empty singleton AString instance
	 * @return Empty string
	 */
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

	public static AString fromStream(InputStream inputStream) throws IOException {
		return create(Blobs.fromStream(inputStream));
	}






}
