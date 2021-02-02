package convex.core.lang.expanders;

import convex.core.data.ACell;
import convex.core.data.Syntax;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.Context;

public abstract class BaseExpander extends AExpander {

	@Override
	public boolean isCanonical() {
		return false;
	}


	@Override
	public int encode(byte[] bs, int pos) {
		throw new UnsupportedOperationException();
	}
	
	@Override
	public int encodeRaw(byte[] bs, int pos) {
		throw new UnsupportedOperationException();
	}

	@Override
	public void ednString(StringBuilder sb) {
		sb.append("#expander " + this.getClass().getName());
	}

	@Override
	public abstract Context<Syntax> expand(ACell form, AExpander ex, Context<?> context);


	@Override
	public int estimatedEncodingSize() {
		return 100;
	}

	@Override
	public void validateCell() throws InvalidDataException {
		// TODO
	}
	
	@Override
	public int getRefCount() {
		return 0;
	}

}
