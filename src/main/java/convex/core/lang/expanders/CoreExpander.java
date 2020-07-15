package convex.core.lang.expanders;

import java.nio.ByteBuffer;

import convex.core.data.Symbol;
import convex.core.data.Syntax;
import convex.core.data.Tag;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.Context;
import convex.core.lang.impl.ICoreDef;

/**
 * Abstract base class for expanders provided in the core language
 */
public abstract class CoreExpander extends BaseExpander implements ICoreDef {

	private Symbol symbol;

	protected CoreExpander(Symbol symbol) {
		this.symbol = symbol;
	}

	@Override
	public Symbol getSymbol() {
		return symbol;
	}

	@Override
	public void ednString(StringBuilder sb) {
		sb.append(symbol.getName());
	}

	@Override
	public ByteBuffer writeRaw(ByteBuffer bb) {
		bb = symbol.writeRaw(bb);
		return bb;
	}

	@Override
	public ByteBuffer write(ByteBuffer b) {
		b = b.put(Tag.CORE_DEF);
		return writeRaw(b);
	}

	@Override
	public int estimatedEncodingSize() {
		return 30;
	}

	@Override
	public boolean isCanonical() {
		return true;
	}

	@Override
	public abstract Context<Syntax> expand(Object form, AExpander cont, Context<?> context);

	@Override
	public void validateCell() throws InvalidDataException {
		super.validateCell();
		symbol.validateCell();
	}
}
