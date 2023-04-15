package convex.core.transactions;

import convex.core.Constants;
import convex.core.Result;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Address;
import convex.core.data.Format;
import convex.core.data.Keyword;
import convex.core.data.Keywords;
import convex.core.data.Ref;
import convex.core.data.Tag;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.Context;
import convex.core.lang.impl.RecordFormat;

public class Multi extends ATransaction {

	protected Ref<AVector<ATransaction>> txs;

	private int mode;
	
	/**
	 * Mode to execute and report all transactions, regardless of outcome
	 */
	public int MODE_ANY=0; 
	
	/**
	 * Mode to execute all transactions iff all succeed
	 */
	public int MODE_ALL=1; 
	
	/**
	 * Mode to execute up to the first successful transaction result
	 */
	public int MODE_FIRST=2; 
	
	/**
	 * Mode to execute up to the first failed transaction result
	 */
	public int MODE_UNTIL=3; 


	
	private static final Keyword[] KEYS = new Keyword[] { Keywords.ORIGIN, Keywords.SEQUENCE,Keywords.MODE,Keywords.TXS};
	private static final RecordFormat FORMAT = RecordFormat.of(KEYS);

	
	protected Multi(Address origin, long sequence, int mode, Ref<AVector<ATransaction>> txs) {
		super(FORMAT, origin, sequence);
		this.mode=mode;
		this.txs=txs;
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
		pos = Format.writeVLCLong(bs,pos, mode);
		pos = txs.encode(bs, pos);
		return pos;
	}

	@Override
	public int estimatedEncodingSize() {
		// TODO Better estimate?
		return 100;
	}

	@Override
	public <T extends ACell> Context<T> apply(Context<?> ctx) {
		AVector<ATransaction> ts=txs.getValue();
		// Context<?> initialContext=ctx.fork();
		long n=ts.count();
		AVector<Result> rs=Vectors.empty();
		for (int i=0; i<n; i++) {
			ATransaction t=ts.get(i);
			ctx=t.apply(ctx);
			Result r=Result.fromContext(CVMLong.ZERO, ctx);
			rs=rs.conj(r);
			if (r.isError()) {
				if (mode==MODE_FIRST) break;
				if (mode==MODE_ALL) {
					// TODO: rollback
					break;
				}
			} else {
				if (mode==MODE_UNTIL) break;
			}
		}
		// TODO: failure cases
		Context<T> rctx=ctx.withValue(rs);
		return rctx;
	}

	@Override
	public Long getMaxJuice() {
		// TODO Auto-generated method stub
		return Constants.MAX_TRANSACTION_JUICE;
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
	public ACell get(ACell key) {
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

	@Override
	public RecordFormat getFormat() {
		return FORMAT;
	}

}
