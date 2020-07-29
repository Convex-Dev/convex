package convex.core.lang.ops;

import java.nio.ByteBuffer;

import convex.core.data.AMap;
import convex.core.data.Format;
import convex.core.data.IRefContainer;
import convex.core.data.IRefFunction;
import convex.core.data.MapEntry;
import convex.core.data.Ref;
import convex.core.data.Symbol;
import convex.core.data.Syntax;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.AOp;
import convex.core.lang.Context;
import convex.core.lang.Juice;
import convex.core.lang.Ops;
import convex.core.util.Errors;

/**
 * Op to look up a symbol in the current execution context.
 * 
 * Consumes juice for lookup.
 *
 * @param <T>
 */
public class Lookup<T> extends AOp<T> {

	private final Symbol symbol;

	private Lookup(Symbol symbol) {
		this.symbol = symbol;
	}

	public static <T> Lookup<T> create(Symbol symbol) {
		return new Lookup<T>(symbol);
	}

	public static <T> Lookup<T> create(String key) {
		return create(Symbol.create(key));
	}

	@Override
	public <I> Context<T> execute(Context<I> context) {
		MapEntry<Symbol, T> le = context.lookupLocalEntry(symbol);
		if (le != null) return context.withResult(Juice.LOOKUP, le.getValue());
		MapEntry<Symbol, Syntax> de = context.lookupDynamicEntry(symbol);
		if (de != null) return context.withResult(Juice.LOOKUP_DYNAMIC, de.getValue().getValue());
		return context.lookupSpecial(symbol).consumeJuice(Juice.LOOKUP_DYNAMIC);
	}

	@Override
	public void ednString(StringBuilder sb) {
		sb.append(symbol.toString());
	}

	@Override
	public byte opCode() {
		return Ops.LOOKUP;
	}

	@Override
	public ByteBuffer writeRaw(ByteBuffer b) {
		return Format.write(b, symbol);
	}

	public static <T> Lookup<T> read(ByteBuffer b) throws BadFormatException {
		Symbol sym = Format.read(b);
		return create(sym);
	}

	@Override
	public int getRefCount() {
		return 0;
	}

	@Override
	public <R> Ref<R> getRef(int i) {
		throw new IndexOutOfBoundsException(Errors.badIndex(i));
	}

	@SuppressWarnings("unchecked")
	@Override
	public <N extends IRefContainer> N updateRefs(IRefFunction func) {
		return (N) this;
	}

	@SuppressWarnings("unchecked")
	@Override
	public AOp<T> specialise(AMap<Symbol, Object> binds) {
		if (binds.containsKey(symbol)) {
			// bindings cover lookup
			return Constant.create((T) binds.get(symbol));
		} else {
			return this;
		}
	}

	@Override
	public void validateCell() throws InvalidDataException {
		symbol.validateCell();
	}

}
