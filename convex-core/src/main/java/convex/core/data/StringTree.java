package convex.core.data;

import convex.core.data.util.BlobBuilder;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.text.Text;
import convex.core.util.ErrorMessages;

/**
 * String implementation class wrapping a BlobTree.
 * 
 * This String implementation is used to represent long strings.
 */
public class StringTree extends AString {
	
	public static final int MINIMUM_LENGTH=StringShort.MAX_LENGTH+1;
	
	public static final int MAX_ENCODING_LENGTH = BlobTree.MAX_ENCODING_SIZE;

	private final BlobTree data;
	
	private StringTree(BlobTree data) {
		super(data.count());
		this.data=data;
	}
	
	public static StringTree create(BlobTree b) {
		return new StringTree(b);
	}
	
	public static StringTree create(ABlob b) {
		return create(BlobTree.create(b));
	}

	@Override
	public AString slice(long start, long end) {
		ABlob newData=data.slice(start, end);
		if (data==newData) return this;
		if (newData==null) return null;
		return Strings.create(newData);
	}

	@Override
	public void validateCell() throws InvalidDataException {
		data.validateCell();
	}

	@Override
	public int encodeRaw(byte[] bs, int pos) {
		return data.encodeRaw(bs, pos);
	}
	
	@Override
	public int writeRawData(byte[] bs, int pos) {
		return data.getBytes(bs, pos);
	}
	
	/**
	 * Reads a StringTree from the given Blob encoding.
	 * 
	 * @param length Length of StringTree in bytes
	 * @param b Blob to read from
	 * @param pos Start position in Blob (location of tag byte)
	 * @return New decoded instance
	 * @throws BadFormatException In the event of any encoding error
	 */
	public static StringTree read(long length, Blob b, int pos) throws BadFormatException {
		BlobTree bt=BlobTree.read(length,b,pos);
		StringTree result= new StringTree(bt);
		result.attachEncoding(bt.getEncoding());
		bt.attachEncoding(null); // invalidate this, since assumed tag will be wrong
		return result;
	}


	@Override
	public int estimatedEncodingSize() {
		return data.estimatedEncodingSize();
	}

	@Override
	public boolean isCanonical() {
		return data.isCanonical();
	}
	
	@Override public final boolean isCVMValue() {
		return true;
	}

	@Override
	public int getRefCount() {
		return data.getRefCount();
	}

	@Override
	public <R extends ACell> Ref<R> getRef(int i) {
		return data.getRef(i);
	}

	@Override
	public StringTree updateRefs(IRefFunction func) {
		BlobTree bt2=data.updateRefs(func);
		if (bt2==data) return this;
		return new StringTree(bt2);
	}

	@Override
	public StringTree toCanonical() {
		return this;
	}

	@Override
	public byte byteAt(long i) {
		if ((i<0)||(i>=length)) return -1;
		return data.byteAt(i);
	}
	
	@Override
	public int intAt(long i) {
		// TODO: potentially faster implementation
		return super.intAt(i);
	}

	@Override
	public int compareTo(ABlobLike<?> o) {
		return data.compareTo(o);
	}

	@Override
	public BlobTree toBlob() {
		return data;
	}

	@Override
	protected void printEscaped(BlobBuilder sb, long start, long end) {
		// TODO This could potentially be faster
		long n=count();
		if ((start<0)||(start>end)||(end>n)) throw new IllegalArgumentException(ErrorMessages.badRange(start, end));
		for (long i=start; i<end; i++) {
			byte b=data.byteAtUnchecked(i);
			Text.writeEscapedByte(sb,b);
		}
		return;
		
	}

	@Override
	public boolean equals(AString b) {
		if (this==b) return true;
		if (b==null) return false;
		if (count()!=b.count()) return false;
		return ACell.genericEquals(this, b);
	}

	@Override
	public boolean equalsBytes(ABlob key) {
		return data.equalsBytes(key);
	}
	
	@Override
	public long longValue() {
		return data.longValue();
	}

}
