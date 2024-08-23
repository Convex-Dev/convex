package convex.core.transactions;

import convex.core.ErrorCodes;
import convex.core.Result;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.AccountStatus;
import convex.core.data.Address;
import convex.core.data.Blob;
import convex.core.data.Format;
import convex.core.data.IRefFunction;
import convex.core.data.Keyword;
import convex.core.data.Keywords;
import convex.core.data.Ref;
import convex.core.data.Tag;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.Context;
import convex.core.lang.RecordFormat;

/**
 * The Multi class enables multiple child transactions to be grouped into a single 
 * wrapper transaction with useful joint execution semantics.
 * 
 * Important notes:
 * - Child transactions must either have the same origin address, or be
 *   for accounts controlled by the top level origin Address
 * - Sequence numbers on child transactions are ignored
 * - All transactions currently share the same juice limit / memory allowance
 */
public class Multi extends ATransaction {

	protected Ref<AVector<ATransaction>> txs;

	private int mode;
	
	/**
	 * Mode to execute and report all transactions, regardless of outcome. 
	 * Equivalent to executing transactions independently.
	 */
	public static final int MODE_ANY=0; 
	
	/**
	 * Mode to execute all transactions iff all succeed. 
	 * Will rollback state changes if any fail.
	 */
	public static final int MODE_ALL=1; 
	
	/**
	 * Mode to execute up to the first transaction succeeds. 
	 * State changes resulting from the first successful transaction only will be applied.
	 */
	public static final int MODE_FIRST=2; 
	
	/**
	 * Mode to execute until the transaction fails. 
	 * Transactions beyond the first failure will not be attempted.
	 */
	public static final int MODE_UNTIL=3; 


	
	private static final Keyword[] KEYS = new Keyword[] { Keywords.ORIGIN, Keywords.SEQUENCE,Keywords.MODE,Keywords.TXS};
	private static final RecordFormat FORMAT = RecordFormat.of(KEYS);

	
	protected Multi(Address origin, long sequence, int mode, Ref<AVector<ATransaction>> txs) {
		super(FORMAT.count(), origin, sequence);
		this.mode=mode;
		this.txs=txs;
	}
	
	public static Multi create(Address origin, long sequence, int mode, ATransaction... txs) {
		AVector<ATransaction> v= Vectors.create(txs);
		return new Multi(origin,sequence,mode,v.getRef());
	}

	public int getMode() {
		return mode;
	}
	
	@Override
	public int encode(byte[] bs, int pos) {
		bs[pos++] = Tag.MULTI;
		return encodeRaw(bs,pos);
	}
	
	@Override
	public int encodeRaw(byte[] bs, int pos) {
		pos = super.encodeRaw(bs,pos); // origin, sequence
		pos = Format.writeVLCCount(bs,pos, mode);
		pos = txs.encode(bs, pos);
		return pos;
	}
	

	public static Multi read(Blob b, int pos) throws BadFormatException {
		int epos=pos+1; // skip tag
		
		long aval=Format.readVLCCount(b,epos);
		Address origin=Address.create(aval);
		epos+=Format.getVLCCountLength(aval);
		
		long sequence = Format.readVLCCount(b,epos);
		epos+=Format.getVLCCountLength(sequence);

		long mode = Format.readVLCCount(b,epos);
		if (!isValidMode(mode)) throw new BadFormatException("Invalid Multi transaction mode: "+mode);
		epos+=Format.getVLCCountLength(mode);
		
		Ref<AVector<ATransaction>> txs=Format.readRef(b, epos);
		epos+=txs.getEncodingLength();
		
		Multi result=new Multi(origin,sequence,(int)mode,txs);
		result.attachEncoding(b.slice(pos,epos));
		return result;
	}

	private static boolean isValidMode(long mode) {
		return (mode>=MODE_ANY)&&(mode<=MODE_UNTIL);
	}

	@Override
	public int estimatedEncodingSize() {
		return 30+Format.MAX_EMBEDDED_LENGTH;
	}

	@Override
	public Context apply(Context ctx) {
		// save initial context, we might need this for rollback
		Context ictx=ctx.fork(); 
		
		AVector<ATransaction> ts=txs.getValue();
		// Context<?> initialContext=ctx.fork();
		long n=ts.count();
		AVector<Result> rs=Vectors.empty();
		for (int i=0; i<n; i++) {
			ATransaction t=ts.get(i);
			
			ctx=applySubTransaction(ctx,t);
			Result r=Result.fromContext(ctx);
			rs=rs.conj(r);
			if (r.isError()) {
				if (mode==MODE_UNTIL) break;
				if (mode==MODE_ALL) {
					// rollback
					break;
				}
			} else {
				if (mode==MODE_FIRST) break;
			}
		}
		
		Context rctx;
		if (ctx.isError()&&(mode==MODE_ALL)) {
			ctx=ictx.handleStateResults(ctx, true);
			rctx=ctx.withError(ErrorCodes.CHILD, rs);
		} else {
			rctx=ctx.withResult(rs);
		}
		return rctx;
	}

	private Context applySubTransaction(Context ctx, ATransaction t) {
		Address torigin=t.origin;
		if (!this.origin.equals(torigin)) {
			// different origin account, so need to check control right
			AccountStatus as=ctx.getAccountStatus(torigin);
			if (as==null) return ctx.withError(ErrorCodes.NOBODY,"Child transaction origin account does not exist");
			ACell cont=as.getController();
			if ((cont==null)||!this.origin.equals(cont)) {
				return ctx.withError(ErrorCodes.TRUST,"Account control not available");
			}
		}
		
		Context childContext=ctx.forkWithAddress(torigin);
		childContext = t.apply(childContext);
		ctx=ctx.handleStateResults(childContext,false);
		return ctx;
	}

	@Override
	public ATransaction withSequence(long newSequence) {
		if (sequence==newSequence) return this;
		return new Multi(origin,newSequence,mode,txs);
	}

	@Override
	public ATransaction withOrigin(Address newAddress) {
		if (newAddress==origin) return this;
		return new Multi(newAddress,sequence,mode,txs);
	}

	@Override
	public byte getTag() {
		return Tag.MULTI;
	}

	@Override
	public void validateCell() throws InvalidDataException {
		// TODO Auto-generated method stub
		if ((mode<MODE_ANY)||(mode>MODE_UNTIL)) throw new InvalidDataException("Illegal mode: "+mode,this);
	}
	
	@Override
	public ACell get(Keyword key) {
		if (Keywords.ORIGIN.equals(key)) return origin;
		if (Keywords.SEQUENCE.equals(key)) return CVMLong.create(sequence);
		if (Keywords.MODE.equals(key)) return CVMLong.create(mode);
		if (Keywords.TXS.equals(key)) return txs.getValue();
		return null;
	}

	@Override
	public int getRefCount() {
		// Always just one Ref
		return 1;
	}
	
	@SuppressWarnings("unchecked")
	@Override
	public <R extends ACell> Ref<R> getRef(int i) {
		if (i==0) return (Ref<R>) txs;
		throw new IndexOutOfBoundsException(i);
	}
	
	@SuppressWarnings("unchecked")
	@Override
	public Multi updateRefs(IRefFunction func) {
		Ref<AVector<ATransaction>> ntxs=(Ref<AVector<ATransaction>>) func.apply(txs);
		if (ntxs==txs) return this;
		return new Multi(origin,sequence,mode,ntxs);
	}

	@Override
	public RecordFormat getFormat() {
		return FORMAT;
	}


}
