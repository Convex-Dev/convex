package convex.core.cvm.ops;

import java.util.HashMap;

import convex.core.cvm.AOp;
import convex.core.cvm.Context;
import convex.core.cvm.Juice;
import convex.core.cvm.Ops;
import convex.core.data.ACell;
import convex.core.data.Blob;
import convex.core.data.IRefFunction;
import convex.core.data.Symbol;
import convex.core.data.Symbols;
import convex.core.data.prim.CVMDouble;
import convex.core.data.prim.CVMLong;
import convex.core.data.util.BlobBuilder;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.util.Utils;

/**
 * Op representing special Symbols like *address* and *caller*
 * 
 * @param <T> Type of special value
 */
public class Special<T extends ACell> extends AOp<T> {
	
	private final byte specialCode;
	
	private static int NUM_SPECIALS=24;
	private static final int BASE=0;
	private static final int LIMIT=BASE+NUM_SPECIALS;
	public static final Symbol[] SYMBOLS=new Symbol[NUM_SPECIALS];
	private static final Special<?>[] specials=new Special[NUM_SPECIALS];
	private static final HashMap<Symbol,Integer> opcodes=new HashMap<>();

	private static final byte S_JUICE=BASE+0;
	private static final byte S_CALLER=BASE+1;
	private static final byte S_ADDRESS=BASE+2;
	private static final byte S_MEMORY=BASE+3;
	private static final byte S_BALANCE=BASE+4;
	private static final byte S_ORIGIN=BASE+5;
	private static final byte S_RESULT=BASE+6;
	private static final byte S_TIMESTAMP=BASE+7;
	private static final byte S_DEPTH=BASE+8;
	private static final byte S_OFFER=BASE+9;
	private static final byte S_STATE=BASE+10;
	private static final byte S_HOLDINGS=BASE+11;
	private static final byte S_SEQUENCE=BASE+12;
	private static final byte S_KEY=BASE+13;
	private static final byte S_JUICE_PRICE=BASE+14;
	private static final byte S_SCOPE=BASE+15;
	private static final byte S_JUICE_LIMIT=BASE+16;
	private static final byte S_CONTROLLER=BASE+17;
	private static final byte S_ENV=BASE+18;
	private static final byte S_PARENT=BASE+19;
	private static final byte S_NOP=BASE+20;
	private static final byte S_MEMORY_PRICE=BASE+21;
	private static final byte S_SIGNER=BASE+22;
	private static final byte S_PEER=BASE+23;

	static {
		reg(S_JUICE,Symbols.STAR_JUICE);
		reg(S_CALLER,Symbols.STAR_CALLER);
		reg(S_ADDRESS,Symbols.STAR_ADDRESS);
		reg(S_MEMORY,Symbols.STAR_MEMORY);
		reg(S_BALANCE,Symbols.STAR_BALANCE);
		reg(S_ORIGIN,Symbols.STAR_ORIGIN);
		reg(S_RESULT,Symbols.STAR_RESULT);
		reg(S_TIMESTAMP,Symbols.STAR_TIMESTAMP);
		reg(S_DEPTH,Symbols.STAR_DEPTH);
		reg(S_OFFER,Symbols.STAR_OFFER);
		reg(S_STATE,Symbols.STAR_STATE);
		reg(S_HOLDINGS,Symbols.STAR_HOLDINGS);
		reg(S_SEQUENCE,Symbols.STAR_SEQUENCE);
		reg(S_KEY,Symbols.STAR_KEY);
		reg(S_JUICE_PRICE,Symbols.STAR_JUICE_PRICE);
		reg(S_SCOPE,Symbols.STAR_SCOPE);
		reg(S_JUICE_LIMIT,Symbols.STAR_JUICE_LIMIT);
		reg(S_CONTROLLER,Symbols.STAR_CONTROLLER);
		reg(S_ENV,Symbols.STAR_ENV);
		reg(S_PARENT,Symbols.STAR_PARENT);
		reg(S_NOP,Symbols.STAR_NOP);
		reg(S_MEMORY_PRICE,Symbols.STAR_MEMORY_PRICE);
		reg(S_SIGNER,Symbols.STAR_SIGNER);
		reg(S_PEER,Symbols.STAR_PEER);
	}
	
	private static byte reg(byte opCode, Symbol sym) {
		int i=opCode-BASE;
		SYMBOLS[i]=sym;
		Special<?> special=new Special<>(opCode);
		specials[i]=special;
		opcodes.put(sym,Integer.valueOf(opCode));
		return opCode;
	}

	
	private Special(byte specialCode) {
		this.specialCode=specialCode;
	}
	

	/**
	 * Creates special Op for the given opCode
	 * @param opCode Special opcode
	 * @return Special instance, or null if not found
	 */
	public static final Special<?> create(int opCode) {
		if ((opCode<BASE)||(opCode>LIMIT)) return null;
		return specials[opCode-BASE];
	}
	
	@Override
	public Context execute(Context ctx) {
		switch (specialCode) {
		case S_JUICE: ctx= ctx.withResult(CVMLong.create(ctx.getJuiceUsed())); break;
		case S_CALLER: ctx= ctx.withResult(ctx.getCaller()); break;
		case S_ADDRESS: ctx= ctx.withResult(ctx.getAddress()); break;
		case S_MEMORY: ctx= ctx.withResult(CVMLong.create(ctx.getAccountStatus().getMemory())); break;
		case S_BALANCE: ctx= ctx.withResult(CVMLong.create(ctx.getBalance())); break;
		case S_ORIGIN: ctx= ctx.withResult(ctx.getOrigin()); break;
		case S_RESULT: break; // unchanged context with current result
		case S_TIMESTAMP: ctx= ctx.withResult(ctx.getState().getTimestamp()); break;
		case S_DEPTH: ctx= ctx.withResult(CVMLong.create(ctx.getDepth()-1)); break; // Depth before executing this Op
		case S_OFFER: ctx= ctx.withResult(CVMLong.create(ctx.getOffer())); break;
		case S_STATE: ctx= ctx.withResult(ctx.getState()); break;
		case S_HOLDINGS: ctx= ctx.withResult(ctx.getHoldings()); break;
		case S_SEQUENCE: ctx= ctx.withResult(CVMLong.create(ctx.getAccountStatus().getSequence())); break;
		case S_KEY: ctx= ctx.withResult(ctx.getAccountStatus().getAccountKey()); break;
		case S_JUICE_PRICE: ctx= ctx.withResult(ctx.getState().getJuicePrice()); break;
		case S_JUICE_LIMIT: ctx= ctx.withResult(CVMLong.create(ctx.getJuiceLimit())); break;
		case S_SCOPE: ctx= ctx.withResult(ctx.getScope()); break;
		case S_CONTROLLER: ctx= ctx.withResult(ctx.getAccountStatus().getController()); break;
		case S_ENV: ctx= ctx.withResult(ctx.getEnvironment()); break;
		case S_PARENT: ctx= ctx.withResult(ctx.getAccountStatus().getParent()); break;
		case S_NOP: break; // unchanged context, propagates *result*
		case S_MEMORY_PRICE: ctx=ctx.withResult(CVMDouble.create(ctx.getState().getMemoryPrice())); break ;
		case S_SIGNER: ctx=ctx.withResult(null); break; // TODO
		case S_PEER: ctx=ctx.withResult(ctx.getPeer()); break ; // TODO
		
		default:
			throw new Error("Bad Opcode"+specialCode);
		}
		return ctx.consumeJuice(Juice.SPECIAL);
	}

	@Override
	public byte opCode() {
		return Ops.SPECIAL;
	}

	@Override
	public int encodeRaw(byte[] bs, int pos) {
		bs[pos++]=specialCode;
		return pos;
	}

	@Override
	public Special<T> updateRefs(IRefFunction func) {
		return this;
	}

	@Override
	public void validateCell() throws InvalidDataException {
		if ((specialCode<BASE)||(specialCode>=LIMIT)) {
			throw new InvalidDataException("Invalid Special opCode "+specialCode, this);
		}
	}

	@Override
	public int getRefCount() {
		return 0;
	}

	@Override
	public boolean print(BlobBuilder bb, long limit) {
		return SYMBOLS[specialCode-BASE].print(bb,limit);
	}

	/**
	 * Gets the special Op for a given Symbol, or null if not found
	 * @param <R> Result type
	 * @param sym Symbol to look up
	 * @return Special Op or null
	 */
	@SuppressWarnings("unchecked")
	public static <R extends ACell> Special<R> forSymbol(Symbol sym) {
		Integer special=opcodes.get(sym);
		if (special==null) return null;
		return (Special<R>) specials[special-BASE];
	}
	
	public static <R extends ACell> Special<R> get(String string) {
		return forSymbol(Symbol.create(string));
	}


	@SuppressWarnings("unchecked")
	public static <T extends ACell> AOp<T> read(Blob b, int pos) throws BadFormatException {
		int epos=pos+Ops.OP_DATA_OFFSET; // skip tag and opcode to get to data

		byte scode=b.byteAt(epos);
		Special<T> special=(Special<T>) Special.create(scode);
		if (special==null) throw new BadFormatException("Bad OpCode for Special value: "+Utils.toHexString(scode));
		return special;
	}
}
