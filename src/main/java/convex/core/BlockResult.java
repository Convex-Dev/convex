package convex.core;

import java.nio.ByteBuffer;

import convex.core.data.ACell;
import convex.core.data.ARecord;
import convex.core.data.AVector;
import convex.core.data.Format;
import convex.core.data.Keyword;
import convex.core.data.Keywords;
import convex.core.data.Tag;
import convex.core.data.Vectors;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.impl.RecordFormat;
import convex.core.util.Utils;

/**
 * Class representing the result of applying a Block to a State.
 * 
 * Each transaction in the block has a corresponding result entry, which may
 * either be a valid result or an error.
 *
 */
public class BlockResult extends ARecord {
	private State state;
	private AVector<Result> results;
	
	private static final Keyword[] BLOCKRESULT_KEYS = new Keyword[] { Keywords.STATE,
			Keywords.RESULTS};

	private static final RecordFormat FORMAT = RecordFormat.of(BLOCKRESULT_KEYS);


	private BlockResult(State state, AVector<Result> results) {
		super(FORMAT);
		this.state = state;
		this.results = results;
	}

	public static BlockResult create(State state, Result[] results) {
		int n=results.length;
		Object[] rs=new Object[n];
		for (int i=0; i<n; i++) {
			rs[i]=results[i];
		}
		return new BlockResult(state, Vectors.of(rs));
	}
	
	public static BlockResult create(State state, AVector<Result> results) {
		return new BlockResult(state, results);
	}

	public State getState() {
		return state;
	}

	public AVector<Result> getResults() {
		return results;
	}
	
	/**
	 * Checks if a result at a specific position is an error
	 * @param i Index of result in block
	 * @return
	 */
	public boolean isError(long i) {
		return getResult(i).isError();
	}

	public Result getResult(long i) {
		return results.get(i);
	}

	/**
	 * Gets the error code for a given transaction
	 * @param i
	 * @return Error code, or null if the transaction succeeded.
	 */
	public Object getErrorCode(long i) {
		Result result=results.get(i);
		return result.getErrorCode();
	}

	@Override
	protected String ednTag() {
		return "blockresult";
	}

	@Override
	public ACell get(ACell key) {
		if (Keywords.STATE.equals(key)) return state;
		if (Keywords.RESULTS.equals(key)) return results;
		return null;
	}

	@Override
	public byte getTag() {
		return Tag.BLOCK_RESULT;
	}

	@SuppressWarnings("unchecked")
	@Override
	protected BlockResult updateAll(ACell[] newVals) {
		State newState=(State)newVals[0];
		AVector<Result> newResults=(AVector<Result>)newVals[1];
		return create(newState,newResults);
	}

	@Override
	public void validateCell() throws InvalidDataException {
		// TODO Auto-generated method stub
		
	}
	
	@Override
	public void validate() throws InvalidDataException {
		super.validate();
		results.validate();
		state.validate();
		
		long n=results.count();
		for (long i=0; i<n; i++) {
			Object r=results.get(i);
			if (!(r instanceof Result)) throw new InvalidDataException("Not a Result at position "+i+" - found "+Utils.getClassName(r),this);
		}
	}

	@Override
	public int encode(byte[] bs, int pos) {
		bs[pos++]=getTag();
		// generic record writeRaw, handles all fields in declared order
		return encodeRaw(bs,pos);
	}
	
	@Override
	public int encodeRaw(byte[] bs, int pos) {
		pos=state.encode(bs,pos);
		pos=results.encode(bs,pos);
		return pos;
	}
	
	@Override
	public int estimatedEncodingSize() {
		return 1+state.estimatedEncodingSize()+results.estimatedEncodingSize();
	}

	public static BlockResult read(ByteBuffer bb) throws BadFormatException {
		State newState=Format.read(bb);
		if (newState==null) throw new BadFormatException("Null state");
		AVector<Result> newResults=Format.read(bb);
		if (newResults==null) throw new BadFormatException("Null results");
		return create(newState,newResults);
	}
	
}
