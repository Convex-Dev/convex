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
	
	@Override public final boolean isCVMValue() {
		return true;
	}
	
	@Override
	protected long calcMemorySize() {	
		// always embedded and no child Refs, so memory size == 0
		return 0;
	}
	
	public AString getName() {
		return name;
	}

	protected static boolean validateName(CharSequence name2) {
		if (name2 == null) return false;
		int n = name2.length();
		if ((n < 1) || (n > (Constants.MAX_NAME_LENGTH))) {
			return false;
		}
		if (Format.canEncodeUFT8(name2)) return true;
		
		// can't encode, so not a valid name
		return false;
	}

	@Override
	public void validateCell() throws InvalidDataException {
		if (!validateName(name)) throw new InvalidDataException("Invalid name: " + name, this);
	}

}
