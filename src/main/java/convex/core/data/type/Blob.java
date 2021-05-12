package convex.core.data.type;

import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.ARecord;
import convex.core.data.Maps;

/**
 * Type that represents any CVM collection
 */
public class Blob extends AType {

	public static final Blob INSTANCE = new Blob();
	
	private Blob() {
		
	}

	@Override
	public boolean check(ACell value) {
		return (value instanceof ARecord);
	}

	@Override
	public boolean allowsNull() {
		return false;
	}

	@Override
	public String toString() {
		return "Blob";
	}

	@Override
	protected AMap<?,?> defaultValue() {
		return Maps.empty();
	}

	@Override
	protected ABlob implicitCast(ACell a) {
		if (a instanceof ABlob) return (ABlob)a;
		return null;
	}
	
	@Override
	protected Class<? extends ACell> getJavaClass() {
		return ABlob.class;
	}

}
