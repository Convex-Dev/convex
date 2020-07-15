package convex.core.data;

import convex.core.exceptions.InvalidDataException;

/**
 * Abstract based class for symbolic objects (Keywords, Symbols)
 */
public abstract class ASymbolic extends ACell {

	protected final String name;

	public static final int MAX_LENGTH = 32;

	protected ASymbolic(String name) {
		this.name = name;
	}

	protected static boolean validateName(String name) {
		if (name == null) return false;
		int n = name.length();
		if ((n < 1) || (n > (MAX_LENGTH))) {
			return false;
		}
		return true;
	}

	@Override
	public void validateCell() throws InvalidDataException {
		if (!validateName(name)) throw new InvalidDataException("Invalid name: " + name, this);
	}

}
