package convex.core.cvm.ops;

import convex.core.cvm.AOp;
import convex.core.cvm.CVMTag;
import convex.core.cvm.Context;
import convex.core.cvm.Juice;
import convex.core.cvm.Ops;
import convex.core.cvm.Syntax;
import convex.core.data.ACell;
import convex.core.data.Blob;
import convex.core.data.Format;
import convex.core.data.Ref;
import convex.core.data.Symbol;
import convex.core.data.util.BlobBuilder;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.RT;

/**
 * Op that creates a definition in the current environment.
 * 
 * Def may optionally have symbolic metadata attached to the symbol.
 * 
 * @param <T> Type of defined value
 */
public class Def<T extends ACell> extends ACodedOp<T,ACell,AOp<T>> {

	private Def(Ref<ACell> key, Ref<AOp<T>> op) {
		super(CVMTag.OP_DEF,key,op);
		// if (!validKey(key)) throw new IllegalArgumentException("Invalid Def key: "+key);
	}
	
	public static <T extends ACell> Def<T> create(ACell key, Ref<AOp<T>> op) {
		if (!validKey(key)) throw new IllegalArgumentException("Invalid Def key: "+key);
		return new Def<T>(key.getRef(), op);
	}

	public static <T extends ACell> Def<T> create(Syntax key, Ref<AOp<T>> op) {
		if (!validKey(key)) throw new IllegalArgumentException("Invalid Def key: "+key);
		return new Def<T>(key.getRef(), op);
	}

	public static <T extends ACell> Def<T> create(Syntax key, AOp<T> op) {
		return create(key, op.getRef());
	}

	public static <T extends ACell> Def<T> create(Symbol key, AOp<T> op) {
		return new Def<T>(key.getRef(), op.getRef());
	}
	
	public static <T extends ACell> Def<T> create(ACell key, AOp<T> op) {
		return new Def<T>(key.getRef(), op.getRef());
	}
	
	@SuppressWarnings("unchecked")
	public static <T extends ACell> Def<T> create(ACell key) {
		return new Def<T>(Ref.get(key), (Ref<AOp<T>>) Ref.NULL_VALUE);
	}

	public static <T extends ACell> Def<T> create(String key, AOp<T> op) {
		return create(Symbol.create(key), op);
	}

	@Override
	public Context execute(Context ctx) {
		AOp<T> op = Ops.ensureOp(this.value.getValue()); // note: may be null for declare with no value
		ACell symbol=code.getValue();
		
		ACell result;
		if (op!=null) {
			// We are given an op to execute
			ctx = ctx.execute(op);
			if (ctx.isExceptional()) return ctx;
			result = ctx.getResult();
		} else {
			// No op, so just retain current env value (or null if not found)
			result=ctx.getEnvironment().get(Syntax.unwrap(symbol));
		}

		// TODO: defined syntax metadata
		if (symbol instanceof Syntax) {
			Syntax syn=(Syntax)symbol;
			ctx = ctx.defineWithSyntax(syn, result);
		} else {
			ctx=ctx.define((Symbol)symbol, result);
		}
		return ctx.withResult(Juice.DEF, result);
	}




	@Override
	public boolean print(BlobBuilder sb, long limit) {
		sb.append("(def ");
		code.getValue().print(sb,limit); // OK since constant size
		sb.append(' ');
		if (!RT.print(sb, value.getValue(),limit)) return false;
		sb.append(')');
		return sb.check(limit);
	}

	@Override
	public int estimatedEncodingSize() {
		return 100;
	}

	/**
	 * Decodes a Def op from a Blob encoding
	 * @param b Blob to read from
	 * @param pos Start position in Blob (location of tag byte)
	 * @return New decoded instance
	 * @throws BadFormatException In the event of any encoding error
	 */
	public static <T extends ACell> Def<T> read(Blob b, int pos) throws BadFormatException {
		int epos=pos+1; // skip tag and opcode to get to data

		Ref<ACell> symbol = Format.readRef(b,epos);
		epos+=symbol.getEncodingLength();
		
		Ref<AOp<T>> ref = Format.readRef(b,epos);
		epos+=ref.getEncodingLength();
		
		Def<T> result= new Def<T>(symbol, ref);
		result.attachEncoding(b.slice(pos, epos));
		return result;
	}


	@Override
	public void validateCell() throws InvalidDataException {
		
	}

	private static boolean validKey(ACell key) {
		if (key instanceof Symbol) return true;
		if (!(key instanceof Syntax)) return false;
		return ((Syntax)key).getValue() instanceof Symbol;
	}

	@Override
	protected Def<T> rebuild(Ref<ACell> newCode, Ref<AOp<T>> newValue) {
		if ((code==newCode)&&(value==newValue)) return this;
		return new Def<T>(newCode,newValue);
	}




}
