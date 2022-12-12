package convex.core.data;

import java.nio.ByteBuffer;

import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.util.Errors;
import convex.core.util.Text;

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
	public int encodeRawData(byte[] bs, int pos) {
		return data.encodeRawData(bs, pos);
	}
	
	public static StringTree read(long length, ByteBuffer bb) throws BadFormatException {
		BlobTree bt=BlobTree.read(bb,length);
		return new StringTree(bt);
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
	protected byte byteAt(long i) {
		if ((i<0)||(i>=length)) return -1;
		return data.byteAt(i);
	}
	
	@Override
	public int intAt(long i) {
		// TODO: potentially faster implementation
		return super.intAt(i);
	}

	@Override
	public int compareTo(AString o) {
		return data.compareTo(o.toBlob());
	}

	@Override
	public BlobTree toBlob() {
		return data;
	}

	@Override
	protected void writeToBuffer(ByteBuffer bb) {
		data.writeToBuffer(bb);
	}

	@Override
	protected void printEscaped(BlobBuilder sb, long start, long end) {
		// TODO This could potentially be faster
		long n=count();
		if ((start<0)||(start>end)||(end>n)) throw new IllegalArgumentException(Errors.badRange(start, end));
		for (long i=start; i<end; i++) {
			byte b=data.getUnchecked(i);
			Text.writeEscapedByte(sb,b);
		}
		return;
		
	}

}
