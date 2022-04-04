package convex.core.data;

import java.nio.ByteBuffer;

import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.exceptions.TODOException;

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
	public AString subString(long start, long end) {
		return Strings.create(data.slice(start, end-start));
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
	public byte byteAt(long i) {
		if ((i<0)||(i>=length)) return -1;
		return data.byteAt(i);
	}

	@Override
	public int compareTo(AString o) {
		return data.compareTo(o.toBlob());
	}

	@Override
	public int charAt(long i) {
		throw new TODOException();
	}


	@Override
	public BlobTree toBlob() {
		return data;
	}

	@Override
	protected void writeToBuffer(ByteBuffer bb) {
		data.writeToBuffer(bb);
	}

}
