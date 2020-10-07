package convex.core.data;

import java.nio.ByteBuffer;

import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.util.Bits;

public class StringTree extends AString {
	
	public static final int MINIMUM_LENGTH=StringShort.MAX_LENGTH+1;
	
	public static final int BASE_SHIFT = 10;
	public static final int BIT_SHIFT_PER_LEVEL = 4;
	public static final int FANOUT = 1 << BIT_SHIFT_PER_LEVEL;

	private final Ref<AString>[] children;
	private final int shift;

	protected StringTree(int length,Ref<AString>[] children) {
		super(length);
		this.children=children;
		this.shift=calcShift(length);
	}
	
	protected static int calcShift(int length) {
		if (length<=0) throw new IllegalArgumentException("Illegal length: "+length);
		
		int bitCount=32-Bits.leadingZeros(length-1)-1;
		
		int shift=BASE_SHIFT+(Math.floorDiv(bitCount-BASE_SHIFT, BIT_SHIFT_PER_LEVEL)*BIT_SHIFT_PER_LEVEL);
		if (shift<BASE_SHIFT) throw new IllegalArgumentException("Too small: "+length);
		return shift;
	}
	
	private final int childSize() {
		return 1<<shift;
	}
	

	public static StringTree create(String s) {
		int len=s.length();
		if (len<StringTree.MINIMUM_LENGTH) throw new IllegalArgumentException("String too short for StringTree");
		int shift=calcShift(len);
		int childSize=1<<shift;
		int n=calcChildCount(len,shift);
		
		@SuppressWarnings("unchecked")
		Ref<AString>[] children = (Ref<AString>[]) new Ref[n];
		for (int i = 0; i < n; i++) {
			int start=i*childSize;
			AString child=Strings.create(s.substring(start, Math.min(len, start+childSize)));
			Ref<AString> ref = child.getRef();
			children[i] = ref;
		}
		return new StringTree(len,children);
	}

	@Override
	public char charAt(int index) {
		int ci=index<<shift;
		return children[ci].getValue().charAt(index-ci*childSize());
	}

	@Override
	public AString subSequence(int start, int end) {
		return StringSlice.create(this,start,end-start);
	}

	@Override
	public void validateCell() throws InvalidDataException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public ByteBuffer write(ByteBuffer bb) {
		bb.put(Tag.STRING);
		return writeRaw(bb);
	}

	@Override
	public ByteBuffer writeRaw(ByteBuffer bb) {
		bb=Format.writeVLCLong(bb, length);
		int n=children.length;
		for (int i=0; i<n; i++) {
			bb = children[i].writeRawHash(bb);
		}

		return bb;
	}
	
	@SuppressWarnings("unchecked")
	public static StringTree read(int length, ByteBuffer bb) throws BadFormatException {
		int shift=calcShift(length);
		int n=calcChildCount(length, shift);
		
		Ref<AString>[] children = (Ref<AString>[]) new Ref[n];
		for (int i = 0; i < n; i++) {
			Ref<AString> ref = Ref.read(bb);
			children[i] = ref;
		}

		return new StringTree(length,children);
	}

	protected static int calcChildCount(int length, int shift) {
		return ((length-1)>>shift)+1;
	}

	@Override
	public int estimatedEncodingSize() {
		// Usually all children will be non-embedded Refs
		return 10+Ref.MAX_ENCODING_LENGTH*children.length;
	}

	@Override
	public boolean isCanonical() {
		return true;
	}

	@Override
	public int getRefCount() {
		return children.length;
	}




}
