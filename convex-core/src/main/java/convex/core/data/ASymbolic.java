package convex.core.data;

import convex.core.Constants;
import convex.core.data.prim.CVMChar;
import convex.core.exceptions.InvalidDataException;

/**
 * Abstract based class for symbolic objects (Keywords, Symbols)
 */
public abstract class ASymbolic extends ABlobLike<CVMChar> {

	protected final StringShort name;

	protected ASymbolic(StringShort name) {
		this.name =  name;
		this.memorySize=Format.FULL_EMBEDDED_MEMORY_SIZE;
	}
	
	@Override
	@SuppressWarnings("unchecked")
	protected <R extends ACell> Ref<R> createRef() {
		// Create Ref with known embedded status
		Ref<ACell> newRef= RefDirect.create(this,cachedHash(),Ref.VALID_EMBEDDED_FLAGS);
		cachedRef=newRef;
		return (Ref<R>) newRef;
	}
	
	@Override public final boolean isCVMValue() {
		return true;
	}
	
	@Override public final boolean isDataValue() {
		return true;
	}
	
	@Override
	protected long calcMemorySize() {	
		// always embedded and no child Refs, so memory size == 0
		return 0;
	}
	
	@Override
	public final int estimatedEncodingSize() {
		// name length plus tag and 1 byte for count
		return (int)(2+name.count());
	}
	
	/**
	 * Gets the name of this symbolic value
	 * @return Name as a short CVM String
	 */
	public final StringShort getName() {
		return name;
	}

	protected static boolean validateName(AString name) {
		if (name == null) return false;
		long n = name.count();
		if ((n < 1) || (n > (Constants.MAX_NAME_LENGTH))) {
			return false;
		}
		
		// We have a valid name
		return true;
	}
	
	@Override
	public boolean isEmbedded() {
		// Symbolic values are always embedded
		return true;
	}
	
	@Override
	public final int hashCode() {
		return name.hashCode();
	}

	/**
	 * Validates this Symbolic value
	 * @throws InvalidDataException If the symbolic value is invalid
	 */
	@Override
	public abstract void validateCell() throws InvalidDataException;
	
	@Override
	public byte byteAt(long i) {
		// TODO Auto-generated method stub
		return toBlob().byteAt(i);
	}

	@Override
	public long hexMatch(ABlobLike<?> b, long start, long length) {
		return toBlob().hexMatch(b, start, length);
	}

	@Override
	public ABlobLike<CVMChar> empty() {
		// emptying a symbolic value isn't really valid. Return null.
		return null;
	}

	@Override
	public int getBytes(byte[] dest, int destOffset) {
		return toBlob().getBytes(dest, destOffset);
	}

	@Override
	public long longValue() {
		// TODO Auto-generated method stub
		return toBlob().longValue();
	}

	@Override
	public ABlob toBlob() {
		return name.toBlob();
	}

	@Override
	public boolean equalsBytes(ABlob b) {
		return name.equalsBytes(b);
	}

	@Override
	public long count() {
		return name.count();
	}

	@Override
	public Ref<CVMChar> getElementRef(long index) {
		return Ref.get(get(index));
	}
	
	@Override
	public CVMChar get(long index) {
		return name.get(index);
	}

	@Override
	public abstract ABlobLike<CVMChar> slice(long start, long end);
	
	@Override
	public <R extends ACell> Ref<R> getRef(int i) {
		throw new IndexOutOfBoundsException(i);
	}

	@Override
	public ACell updateRefs(IRefFunction func) {
		return this;
	}
	
	@Override
	public int getRefCount() {
		// Never any refs
		return 0;
	}
	
	@Override
	public int getBranchCount() {
		// Never any branches
		return 0;
	}
}
