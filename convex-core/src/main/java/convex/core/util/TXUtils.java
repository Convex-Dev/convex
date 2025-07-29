package convex.core.util;

import convex.core.Result;
import convex.core.cvm.Keywords;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Hash;
import convex.core.lang.RT;

public class TXUtils {

	/**
	 * Gets a transaction ID from a remotely executed result.
	 * 
	 * The transaction must be from a submitted transaction
	 * @param result Result returned from transaction 
	 * @return Transaction ID (Hash)
	 */
	public static Hash getTransactionID(Result result) {
		Hash h=RT.ensureHash(RT.getIn(result, Keywords.INFO,Keywords.TX));
		if (h==null) throw new IllegalArgumentException("Result does not contain transaction hash: "+result);
		return h;
	}
	
	/**
	 * Gets a log from a remotely executed result.
	 * 
	 * The transaction must be from a submitted transaction
	 * @param result Result returned from transaction 
	 * @return Transaction log
	 */
	public static AVector<AVector<ACell>> getTransactionLog(Result result) {
		AVector<AVector<ACell>> log=result.getLog();
		if (log==null) throw new IllegalArgumentException("Result does not contain transaction log: "+result);
		return log;
	}

}
