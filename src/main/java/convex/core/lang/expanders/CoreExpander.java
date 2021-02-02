package convex.core.lang.expanders;

import convex.core.data.ACell;
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
		symbol.ednString(sb);
	}
	
	@Override
	public void print(StringBuilder sb) {
		symbol.print(sb);
	}

	@Override
	public int encode(byte[] bs, int pos) {
		bs[pos++]=Tag.CORE_DEF;
		return encodeRaw(bs,pos);
	}
	
	@Override
	public int encodeRaw(byte[] bs, int pos) {
		// TODO: consider more compact encoding for core functions?
		pos = symbol.encodeRaw(bs,pos);
		return pos;
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
	public abstract Context<Syntax> expand(ACell form, AExpander cont, Context<?> context);

	@Override
	public void validateCell() throws InvalidDataException {
		super.validateCell();
		symbol.validateCell();
	}
}
