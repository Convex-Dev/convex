package convex.core.cvm.transactions;

import convex.core.ErrorCodes;
import convex.core.Result;
import convex.core.cvm.ARecordGeneric;
import convex.core.cvm.AccountStatus;
import convex.core.cvm.Address;
import convex.core.cvm.CVMTag;
import convex.core.cvm.Context;
import convex.core.cvm.Keywords;
import convex.core.cvm.RecordFormat;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Keyword;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.RT;
import convex.core.util.ErrorMessages;
import convex.core.util.Utils;

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

	protected AVector<ATransaction> txs;

	private final int mode;
	
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

	// Record key indexes
	protected static final int IX_MODE=2;
	protected static final int IX_TXS=3;

	
	protected Multi(Address origin, long sequence, int mode, AVector<ATransaction> txs) {
		super(CVMTag.MULTI,FORMAT, Vectors.create(origin,CVMLong.create(sequence),CVMLong.create(mode),txs));
		this.mode=mode;
		this.txs=txs;
	}
	
	protected Multi(AVector<ACell> values) {
		super(CVMTag.MULTI,FORMAT, values);
		this.mode=Utils.checkedInt(RT.ensureLong(values.get(IX_MODE)).longValue());
		if (!isValidMode(mode)) throw new IllegalArgumentException("Bad mode");
	}

	public static Multi create(Address origin, long sequence, int mode, ATransaction... txs) {
		AVector<ATransaction> v= Vectors.create(txs);
		return new Multi(origin,sequence,mode,v);
	}

	public int getMode() {
		return mode;
	}

	public static Multi read(Blob b, int pos) throws BadFormatException {
		AVector<ACell> values=Vectors.read(b, pos);
		int epos=pos+values.getEncodingLength();

		if (values.count()!=KEYS.length) throw new BadFormatException(ErrorMessages.RECORD_VALUE_NUMBER);

		Multi result=new Multi(values);
		result.attachEncoding(b.slice(pos,epos));
		return result;
	}

	private static boolean isValidMode(long mode) {
		return (mode>=MODE_ANY)&&(mode<=MODE_UNTIL);
	}



	@Override
	public Context apply(Context ctx) {
		// save initial context, we might need this for rollback
		Context ictx=ctx.fork(); 
		
		AVector<ATransaction> ts=getTransactions();
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

	private AVector<ATransaction> getTransactions() {
		if (txs==null) {
			txs=RT.ensureVector(values.get(IX_TXS));
		}
		return txs;
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
	public void validateCell() throws InvalidDataException {
		if ((mode<MODE_ANY)||(mode>MODE_UNTIL)) throw new InvalidDataException("Illegal mode: "+mode,this);
	}
	
	@Override
	public ACell get(Keyword key) {
		if (Keywords.MODE.equals(key)) return values.get(IX_MODE);
		if (Keywords.TXS.equals(key)) return getTransactions();
		return super.get(key); // covers origin and sequence
	}

	@Override
	protected ARecordGeneric withValues(AVector<ACell> newValues) {
		if (values==newValues) return this;
		return new Multi(values);
	}


}
