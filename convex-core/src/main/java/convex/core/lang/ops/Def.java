package convex.core.lang.ops;

import java.nio.ByteBuffer;

import convex.core.data.ACell;
import convex.core.data.Blob;
import convex.core.data.BlobBuilder;
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
import convex.core.lang.RT;
import convex.core.util.Errors;

/**
 * Op that creates a definition in the current environment.
 * 
 * Def may optionally have symbolic metadata attached to the symbol.
 * 
 * @param <T> Type of defined value
 */
public class Def<T extends ACell> extends AOp<T> {

	// symbol is either:
	// - Syntax Object including metadata to add to the defined environment
	// - Raw symbol, no change to metadata 
	// (TODO: do we want this distinction?)
	private final ACell symbol;
	
	// expression to execute to determine the defined value
	private final Ref<AOp<T>> op;

	private Def(ACell key, Ref<AOp<T>> op) {
		this.op = op;
		this.symbol = key;
		if (symbol==null) throw new IllegalArgumentException("Null key in Def!!!");
	}
	
	public static <T extends ACell> Def<T> create(ACell key, Ref<AOp<T>> op) {
		if (!validKey(key)) throw new IllegalArgumentException("Invalid Def key: "+key);
		return new Def<T>(key, op);
	}

	public static <T extends ACell> Def<T> create(Syntax key, Ref<AOp<T>> op) {
		if (!validKey(key)) throw new IllegalArgumentException("Invalid Def key: "+key);
		return new Def<T>(key, op);
	}

	public static <T extends ACell> Def<T> create(Syntax key, AOp<T> op) {
		return create(key, op.getRef());
	}

	public static <T extends ACell> Def<T> create(Symbol key, AOp<T> op) {
		return new Def<T>(key, op.getRef());
	}
	
	public static <T extends ACell> Def<T> create(ACell key, AOp<T> op) {
		return new Def<T>(key, op.getRef());
	}

	public static <T extends ACell> Def<T> create(String key, AOp<T> op) {
		return create(Symbol.create(key), op);
	}

	@Override
	public Context execute(Context context) {
		Context ctx = context.execute(op.getValue());
		if (ctx.isExceptional()) return ctx;
		
		ACell opResult = ctx.getResult();

		// TODO: defined syntax metadata
		if (symbol instanceof Syntax) {
			Syntax syn=(Syntax)symbol;
			ctx = ctx.defineWithSyntax(syn, opResult);
		} else {
			ctx=ctx.define((Symbol)symbol, opResult);
		}
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
		ACell newSymbol=symbol.updateRefs(func);
		Ref<AOp<T>> newRef = (Ref<AOp<T>>) func.apply(op);
		if (op == newRef) return this;
		return new Def<T>(newSymbol, newRef);
	}

	@Override
	public boolean print(BlobBuilder sb, long limit) {
		sb.append("(def ");
		symbol.print(sb,limit); // OK since constant size
		sb.append(' ');
		if (!RT.print(sb, op.getValue(),limit)) return false;
		sb.append(')');
		return sb.check(limit);
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
		ACell symbol = Format.read(b);
		Ref<AOp<T>> ref = Format.readRef(b);
		if (!validKey(symbol)) throw new BadFormatException("Symbol not valid for Def op");
		return new Def<>(symbol, ref);
	}
	

	public static <T extends ACell> Def<T> read(Blob b, int pos) throws BadFormatException {
		int epos=pos+2; // skip tag and opcode
		ACell symbol = Format.read(b,epos);
		epos+=Format.getEncodingLength(symbol);
		
		Ref<AOp<T>> ref = Format.readRef(b,epos);
		if (!validKey(symbol)) throw new BadFormatException("Symbol not valid for Def op");
		epos+=ref.getEncodingLength();
		
		Def<T> result= new Def<>(symbol, ref);
		result.attachEncoding(b.slice(pos, epos));
		return result;
	}


	@Override
	public void validateCell() throws InvalidDataException {
		if (!validKey(symbol)) {
			throw new InvalidDataException("Def requires a Symbol or Syntax Object for definition but was: "+RT.getType(symbol),this);
		}
		symbol.validateCell();
	}

	private static boolean validKey(ACell key) {
		if (key instanceof Symbol) return true;
		if (!(key instanceof Syntax)) return false;
		return ((Syntax)key).getValue() instanceof Symbol;
	}


}
