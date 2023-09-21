package convex.core.lang.ops;

import java.util.HashMap;

import convex.core.data.ACell;
import convex.core.data.BlobBuilder;
import convex.core.data.IRefFunction;
import convex.core.data.Symbol;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.AOp;
import convex.core.lang.Context;
import convex.core.lang.Juice;
import convex.core.lang.Ops;
import convex.core.lang.Symbols;

/**
 * Op representing special Symbols like *address* and *caller*
 * 
 * @param <T> Type of special value
 */
public class Special<T extends ACell> extends AOp<T> {
	
	private final byte opCode;
	
	private static int NUM_SPECIALS=17;
	private static final int BASE=Ops.SPECIAL_BASE;
	private static final int LIMIT=BASE+NUM_SPECIALS;
	private static final Symbol[] symbols=new Symbol[NUM_SPECIALS];
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
	}
	
	private static byte reg(byte opCode, Symbol sym) {
		int i=opCode-BASE;
		symbols[i]=sym;
		Special<?> special=new Special<>(opCode);
		specials[i]=special;
		opcodes.put(sym,Integer.valueOf(opCode));
		return opCode;
	}

	
	private Special(byte opCode) {
		this.opCode=opCode;
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
		switch (opCode) {
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
		default:
			throw new Error("Bad Opcode"+opCode);
		}
		return ctx.consumeJuice(Juice.SPECIAL);
	}

	@Override
	public byte opCode() {
		return opCode;
	}

	@Override
	public int encodeRaw(byte[] bs, int pos) {
		// No data
		return pos;
	}

	@Override
	public Special<T> updateRefs(IRefFunction func) {
		return this;
	}

	@Override
	public void validateCell() throws InvalidDataException {
		if ((opCode<BASE)||(opCode>=LIMIT)) {
			throw new InvalidDataException("Invalid Special opCode "+opCode, this);
		}
	}

	@Override
	public int getRefCount() {
		return 0;
	}

	@Override
	public boolean print(BlobBuilder bb, long limit) {
		return symbols[opCode-BASE].print(bb,limit);
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
}
