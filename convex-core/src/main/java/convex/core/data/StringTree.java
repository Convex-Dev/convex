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
	
	/**
	 * Gets the length of each full child String
	 * @return
	 */
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

	private int childIndexAt(int index) {
		int ci=index>>shift;
		return ci;
	}
	
	@Override
	public char charAt(int index) {
		int ci=index>>shift;
		int cix=index-ci*childSize();
		return children[ci].getValue().charAt(cix);
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
	public int encodeRaw(byte[] bs, int pos) {
		pos=Format.writeVLCLong(bs,pos, length);
		int n=children.length;
		for (int i=0; i<n; i++) {
			pos = children[i].encode(bs,pos);
		}
		return pos;
	}
	
	@SuppressWarnings("unchecked")
	public static StringTree read(int length, ByteBuffer bb) throws BadFormatException {
		int shift=calcShift(length);
		int n=calcChildCount(length, shift);
		
		Ref<AString>[] children = (Ref<AString>[]) new Ref[n];
		for (int i = 0; i < n; i++) {
			Ref<AString> ref = Format.readRef(bb);
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
		return 10+Ref.INDIRECT_ENCODING_LENGTH*children.length;
	}

	@Override
	public boolean isCanonical() {
		return true;
	}
	
	@Override public final boolean isCVMValue() {
		return true;
	}

	@Override
	public int getRefCount() {
		return children.length;
	}

	@SuppressWarnings("unchecked")
	@Override
	public <R extends ACell> Ref<R> getRef(int i) {
		int ic = children.length;
		if (i < 0) throw new IndexOutOfBoundsException("Negative Ref index: " + i);
		if (i < ic) return (Ref<R>) children[i];
		throw new IndexOutOfBoundsException("Ref index out of range: " + i);
	}

	@SuppressWarnings("unchecked")
	@Override
	public StringTree updateRefs(IRefFunction func) {
		int ic = children.length;
		for (int i = 0; i < ic; i++) {
			Ref<AString> current = children[i];
			Ref<AString> newChild = (Ref<AString>) func.apply(current);
			children[i] = newChild;
		}
		return this;
	}

	@Override
	protected void appendToStringBuffer(StringBuilder sb, int start, int length) {
		int cstart=childIndexAt(start);
		int cend=childIndexAt(start+length-1);
		int csize=childSize();
		for (int i=cstart; i<=cend; i++) {
			AString child=children[i].getValue();
			
			// compute indexes indo child
			int c0=Math.max(0, start-i*csize);
			int c1=Math.min(child.length, start+length-i*csize);
			child.appendToStringBuffer(sb, c0, c1-c0);
		}
	}

	@Override
	protected AString append(char charValue) {
		// TODO: SECURITY: needs to be O(1)
		StringBuilder sb=new StringBuilder();
		appendToStringBuffer(sb, 0, length);
		sb.append(charValue);
		return Strings.create(sb.toString());
	}

	@Override
	public StringTree toCanonical() {
		return this;
	}


}
