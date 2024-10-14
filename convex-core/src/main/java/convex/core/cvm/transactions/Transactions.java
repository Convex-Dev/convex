package convex.core.cvm.transactions;

import java.util.HashMap;

import convex.core.lang.RT;

public class Transactions {

	public static HashMap<String,Object> toJSON(ATransaction tx) {
		HashMap<String,Object> result= RT.jsonMap(tx);
		result.put("type", tx.getClass().getSimpleName());
		return result;
	}
}
