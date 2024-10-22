package convex.core.lang.impl;

import convex.core.cvm.Context;
import convex.core.data.ACell;
import convex.core.data.ADataStructure;
import convex.core.data.Keyword;
import convex.core.data.type.Types;
import convex.core.data.util.BlobBuilder;
import convex.core.lang.RT;

public class KeywordFn<T extends ACell> extends ADataFn<T> {
	private Keyword key;

	public KeywordFn(Keyword k) {
		this.key = k;
	}

	public static <T extends ACell> KeywordFn<T> wrap(Keyword k) {
		return new KeywordFn<T>(k);
	}

	@SuppressWarnings("unchecked")
	@Override
	public Context invoke(Context context, ACell[] args) {
		int n = args.length;
		T result;
		if (n == 1) {
			ADataStructure<?> gettable = RT.ensureAssociative(args[0]);
			if (gettable == null) return context.withCastError(0, Types.DATA_STRUCTURE);
			result = (T) gettable.get(key);
		} else if (n == 2) {
			ACell ds = args[0];
			ACell notFound = args[1];
			if (ds == null) {
				result = (T) notFound;
			} else {
				ADataStructure<?> gettable = RT.ensureAssociative(ds);
				if (gettable == null) return context.withCastError(0, Types.DATA_STRUCTURE);
				result = (T) RT.get(gettable, key, notFound);
			}
		} else {
			return context.withArityError("Expected arity 1 or 2 for keyword lookup but got: " + n);
		}
		return context.withResult(result);
	}

	@Override
	public boolean print(BlobBuilder sb,long limit) {
		return key.print(sb,limit);
	}

	@Override
	public ACell toCanonical() {
		return key.getCanonical();
	}


}
