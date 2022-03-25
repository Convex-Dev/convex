package convex.core.data;

import convex.core.Constants;
import convex.core.exceptions.InvalidDataException;

/**
 * Abstract based class for symbolic objects (Keywords, Symbols)
 */
public abstract class ASymbolic extends ACell {

	protected final AString name;

	protected ASymbolic(AString name) {
		this.name = name;
	}
	
	@Override
	@SuppressWarnings("unchecked")
	protected <R extends ACell> Ref<R> createRef() {
		// Create Ref at maximum status to reflect internal embedded status
		Ref<ACell> newRef= RefDirect.create(this,cachedHash(),Ref.INTERNAL_FLAGS);
		cachedRef=newRef;
		return (Ref<R>) newRef;
	}
	
	@Override public final boolean isCVMValue() {
		return true;
	}
	
	@Override
	protected long calcMemorySize() {	
		// always embedded and no child Refs, so memory size == 0
		return 0;
	}
	
	public abstract AString getName();

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

}
