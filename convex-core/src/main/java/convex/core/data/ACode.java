package convex.core.data;

import convex.core.data.impl.ALongBlob;
import convex.core.util.Utils;
import convex.core.exceptions.InvalidDataException;

/**
 * Abstract base class for CVM code data types
 */
public abstract class ACode extends ALongBlob {

	protected byte tag;
	
	protected ACode(long value) {
		super(value);
	}

	@Override
	public void validateCell() throws InvalidDataException {
		if ((byte)(this.tag&0xF0)!=Tag.CODE_BASE) {
			throw new InvalidDataException("Invalide Code tag: 0x"+Utils.toHexString(tag),this);
		}
		if (this.value<0) {
			throw new InvalidDataException("Negaitive code value",this);
		}
	}
}
