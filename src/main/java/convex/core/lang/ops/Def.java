package convex.core.lang.ops;

import java.nio.ByteBuffer;

import convex.core.data.Format;
import convex.core.data.IRefFunction;
import convex.core.data.Ref;
import convex.core.data.Symbol;
import convex.core.data.Syntax;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.AOp;
import convex.core.lang.Context;
import convex.core.lang.Juice;
import convex.core.lang.Ops;
import convex.core.data.ACell;
import convex.core.util.Errors;
import convex.core.util.Utils;

/**
 * Op that creates a definition in the current environment.
 * 
 * Def may optionally have symbolic metadata attached to the symbol.
 * 
 * @param <T> Type of defined value
 */
public class Def<T extends ACell> extends AOp<T> {

	// symbol Syntax Object including metadata to add to the defined environment
	private final Syntax symbol;
	
	// expression to execute to determine the defined value
	private final Ref<AOp<T>> op;

	private Def(Syntax key, Ref<AOp<T>> op) {
		this.op = op;
		this.symbol = key;
	}

	public static <T extends ACell> Def<T> create(Syntax key, Ref<AOp<T>> op) {
		return new Def<T>(key, op);
	}

	public static <T extends ACell> Def<T> create(Syntax key, AOp<T> op) {
		return create(key, op.getRef());
	}

	public static <T extends ACell> Def<T> create(Symbol key, AOp<T> op) {
		return create(Syntax.create(key), op.getRef());
	}

	public static <T extends ACell> Def<T> create(String key, AOp<T> op) {
		return create(Symbol.create(key), op);
	}

	@Override
	public <I extends ACell> Context<T> execute(Context<I> context) {
		Context<T> ctx = (Context<T>) context.execute(op.getValue());
		if (ctx.isExceptional()) return ctx;
		
		T opResult = ctx.getResult();

		// TODO: defined syntax metadata
		ctx = ctx.define(symbol.getValue(), Syntax.create(opResult,symbol.getMeta()));
		return ctx.withResult(Juice.DEF, opResult);
	}

	@Override
	public int getRefCount() {
		return 1;
	}

	@SuppressWarnings("unchecked")
	@Override
	public Ref<AOp<T>> getRef(int i) {
		if (i != 0) throw new IndexOutOfBoundsException(Errors.badIndex(i));
		return op;
	}

	@SuppressWarnings("unchecked")
	@Override
	public Def<T> updateRefs(IRefFunction func) {
		Ref<AOp<T>> newRef = (Ref<AOp<T>>) func.apply(op);
		if (op == newRef) return this;
		return create(symbol, newRef);
	}

	@Override
	public void ednString(StringBuilder sb) {
		sb.append("(def ");
		sb.append(symbol);
		sb.append(' ');
		Utils.ednString(sb, op.getValue());
		sb.append(')');
	}
	
	@Override
	public void print(StringBuilder sb) {
		sb.append("(def ");
		sb.append(symbol);
		sb.append(' ');
		Utils.print(sb, op.getValue());
		sb.append(')');
	}

	@Override
	public byte opCode() {
		return Ops.DEF;
	}

	@Override
	public int encodeRaw(byte[] bs, int pos) {
		pos = Format.write(bs,pos, symbol);
		pos = op.encode(bs,pos);
		return pos;
	}
	
	@Override
	public int estimatedEncodingSize() {
		return symbol.estimatedEncodingSize()+Format.MAX_EMBEDDED_LENGTH;
	}

	public static <T extends ACell> Def<T> read(ByteBuffer b) throws BadFormatException {
		Syntax symbol = Format.read(b);
		Ref<AOp<T>> ref = Format.readRef(b);
		return create(symbol, ref);
	}

	@Override
	public void validateCell() throws InvalidDataException {
		symbol.validateCell();
	}

}
