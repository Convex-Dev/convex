package convex.core.cpos;

import convex.core.ErrorCodes;
import convex.core.Result;
import convex.core.cvm.ARecordGeneric;
import convex.core.cvm.CVMTag;
import convex.core.cvm.Keywords;
import convex.core.cvm.RecordFormat;
import convex.core.cvm.State;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Cells;
import convex.core.data.Hash;
import convex.core.data.Keyword;
import convex.core.data.Vectors;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.RT;
import convex.core.util.ErrorMessages;
import convex.core.util.Utils;

/**
 * Class representing the result of applying a Block to a State.
 * 
 * Each transaction in the block has a corresponding result entry, which may
 * either be a valid result or an error.
 *
 */
public class BlockResult extends ARecordGeneric {
	private State state;
	private AVector<Result> results;
	
	private static final Keyword[] BLOCKRESULT_KEYS = new Keyword[] { Keywords.STATE, Keywords.RESULTS};
	private static final RecordFormat FORMAT = RecordFormat.of(BLOCKRESULT_KEYS);

	private BlockResult(State state, AVector<Result> results) {
		super(CVMTag.BLOCK_RESULT,FORMAT,Vectors.create(state,results));
		this.state = state;
		this.results = results;
	}

	public BlockResult(AVector<ACell> values) {
		super(CVMTag.BLOCK_RESULT,FORMAT,values);
	}

	/**
	 * Create a BlockResult
	 * @param state Resulting State
	 * @param results Results of transactions in Block
	 * @return BlockResult instance
	 */
	public static BlockResult create(State state, Result[] results) {
		return new BlockResult(state, Vectors.create(results));
	}
	
	/**
	 * Create a BlockResult
	 * @param state Resulting State
	 * @param results Results of transactions in Block
	 * @return BlockResult instance
	 */
	public static BlockResult create(State state, AVector<Result> results) {
		return new BlockResult(state, results);
	}

	/**
	 * Get the State resulting from this Block.
	 * @return State after Block is executed
	 */
	public State getState() {
		if (state==null) state=(State)values.get(0);
		return state;
	}

	/**
	 * Gets the Results of all transactions in the Block
	 * @return Vector of Results
	 */
	public AVector<Result> getResults() {
		if (results==null) results=RT.ensureVector(values.get(1));
		return results;
	}
	
	/**
	 * Checks if a result at a specific position is an error
	 * @param i Index of result in block
	 * @return True if result at index i is an error, false otherwise.
	 */
	public boolean isError(long i) {
		return getResult(i).isError();
	}

	/**
	 * Gets a specific Result
	 * @param i Index of Result
	 * @return Result at specified index for the current Block, or null if not available
	 */
	public Result getResult(long i) {
		AVector<Result> results=getResults();
		if ((i<0)||(i>=results.count())) return null;
		return results.get(i);
	}

	/**
	 * Gets the error code for a given transaction
	 * @param i Index of Result
	 * @return Error code, or null if the transaction succeeded.
	 */
	public ACell getErrorCode(long i) {
		Result result=getResult(i);
		return result.getErrorCode();
	}

	@Override
	public ACell get(Keyword key) {
		if (Keywords.STATE.equals(key)) return state;
		if (Keywords.RESULTS.equals(key)) return results;
		return null;
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
			ACell r=results.get(i);
			if (!(r instanceof Result)) throw new InvalidDataException("Not a Result at position "+i+" - found "+Utils.getClassName(r),this);
		}
	}

	/**
	 * Decodes a BlockResult from a Blob
	 * @param b Blob to read from
	 * @param pos start position in Blob 
	 * @return BlockResult instance
	 * @throws BadFormatException If encoding format has errors
	 */
	public static BlockResult read(Blob b, int pos) throws BadFormatException {
		AVector<ACell> values=Vectors.read(b, pos);
		int epos=pos+values.getEncodingLength();

		if (values.count()!=BLOCKRESULT_KEYS.length) throw new BadFormatException(ErrorMessages.RECORD_VALUE_NUMBER);

		BlockResult result=new BlockResult(values);
		result.attachEncoding(b.slice(pos,epos));
		return result;
	}

	
	@Override 
	public boolean equals(ACell a) {
		if (a instanceof BlockResult)return equals((BlockResult)a);
		return Cells.equalsGeneric(this,a);
	}
	
	/**
	 * Tests if this BlockResult is equal to another
	 * @param a BlockResult to compare with
	 * @return true if equal, false otherwise
	 */
	public boolean equals(BlockResult a) {
		if (this == a) return true; // important optimisation for e.g. hashmap equality
		if (a == null) return false;
		Hash h=this.cachedHash();
		if (h!=null) {
			Hash ha=a.cachedHash();
			if (ha!=null) return Cells.equals(h, ha);
		}
		
		if (!(Cells.equals(results, a.results))) return false;
		if (!(Cells.equals(state, a.state))) return false;
		return true;
	}

	/**
	 * Creates a BlockResult for an invalid Block (e.g. no peer in Global State)
	 * @param state State at time of creation
	 * @param block Invalid block
	 * @param message Message to report to clients
	 * @return BlockResult instance
	 */
	public static BlockResult createInvalidBlock(State state, Block block, AString message) {
		Result r=Result.create(null, message,ErrorCodes.PEER);
		AVector<Result> rs;
		if (block==null) {
			rs=null;
		} else {
			rs=Vectors.repeat(r, block.getTransactions().size());
		}
		
		return new BlockResult(state,rs);
	}

	@Override
	protected ARecordGeneric withValues(AVector<ACell> newValues) {
		if (values==newValues) return this;
		return new BlockResult(newValues);
	}



	
}
