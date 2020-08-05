package convex.core.lang.expanders;

import java.nio.ByteBuffer;

import convex.core.data.Syntax;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.Context;

public abstract class BaseExpander extends AExpander {

	@Override
	public boolean isCanonical() {
		return false;
	}

	@Override
	public ByteBuffer writeRaw(ByteBuffer bb) {
		throw new UnsupportedOperationException();
	}

	@Override
	public void ednString(StringBuilder sb) {
		sb.append("#expander " + this.getClass().getName());
	}

	@Override
	public abstract Context<Syntax> expand(Object form, AExpander ex, Context<?> context);

	@Override
	public ByteBuffer write(ByteBuffer b) {
		throw new UnsupportedOperationException();
	}

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
