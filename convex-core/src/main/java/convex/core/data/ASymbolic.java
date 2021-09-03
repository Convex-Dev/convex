package convex.core.data;

import convex.core.Constants;
import convex.core.exceptions.InvalidDataException;

/**
 * Abstract based class for symbolic objects (Keywords, Symbols)
 */
public abstract class ASymbolic extends ACell {

	protected final String name;

	protected ASymbolic(String name) {
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
	
	public String getName() {
		return name;
	}

	protected static boolean validateName(String name2) {
		if (name2 == null) return false;
		int n = name2.length();
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
	 * Validates the name of this Symbolic value
	 */
	@Override
	public void validateCell() throws InvalidDataException {
		if (!validateName(name)) throw new InvalidDataException("Invalid name: " + name, this);
	}

}
