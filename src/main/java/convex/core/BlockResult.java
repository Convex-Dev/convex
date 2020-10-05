package convex.core;

import java.nio.ByteBuffer;

import convex.core.data.ARecord;
import convex.core.data.AVector;
import convex.core.data.Format;
import convex.core.data.Keyword;
import convex.core.data.Keywords;
import convex.core.data.Tag;
import convex.core.data.Vectors;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.impl.ErrorValue;
import convex.core.lang.impl.RecordFormat;

/**
 * Class representing the result of applying a Block to a State.
 * 
 * Each transaction in the block has a corresponding result entry, which may
 * either be a valid result or an error.
 *
 */
public class BlockResult extends ARecord {
	private State state;
	private AVector<Object> results;
	
	private static final Keyword[] BLOCKRESULT_KEYS = new Keyword[] { Keywords.STATE,
			Keywords.RESULTS};

	private static final RecordFormat FORMAT = RecordFormat.of(BLOCKRESULT_KEYS);


	private BlockResult(State state, AVector<Object> results) {
		super(FORMAT);
		this.state = state;
		this.results = results;
	}

	public static BlockResult create(State state, Object[] results) {
		return new BlockResult(state, Vectors.of(results));
	}
	
	public static BlockResult create(State state, AVector<Object> results) {
		return new BlockResult(state, results);
	}

	public State getState() {
		return state;
	}

	public AVector<Object> getResults() {
		return results;
	}
	
	public boolean isError(long i) {
		return getResult(i) instanceof ErrorValue;
	}

	public Object getResult(long i) {
		return results.get(i);
	}

	/**
	 * Gets the error value for a given transaction
	 * @param i
	 * @return ErrorValue instance, or null if the transaction succeeded.
	 */
	public ErrorValue getError(long i) {
		Object result=results.get(i);
		if (!(result instanceof ErrorValue)) return null;
		return (ErrorValue) result;
	}

	@Override
	protected String ednTag() {
		return "blockresult";
	}

	@SuppressWarnings("unchecked")
	@Override
	public <V> V get(Keyword key) {
		if (Keywords.STATE.equals(key)) return (V) state;
		if (Keywords.RESULTS.equals(key)) return (V) results;
		return null;
	}

	@Override
	public byte getRecordTag() {
		return Tag.BLOCK_RESULT;
	}

	@SuppressWarnings("unchecked")
	@Override
	protected BlockResult updateAll(Object[] newVals) {
		State newState=(State)newVals[0];
		AVector<Object> newResults=(AVector<Object>)newVals[1];
		return create(newState,newResults);
	}

	@Override
	public void validateCell() throws InvalidDataException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public ByteBuffer write(ByteBuffer bb) {
		bb=bb.put(Tag.BLOCK_RESULT);
		bb=state.write(bb);
		bb=results.write(bb);
		return bb;
	}

	public static BlockResult read(ByteBuffer bb) throws BadFormatException {
		State newState=Format.read(bb);
		AVector<Object> newResults=Format.read(bb);
		return create(newState,newResults);
	}
}
