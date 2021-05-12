package convex.core.data.type;

import convex.core.data.ABlob;
import convex.core.data.ACell;

/**
 * Type that represents any CVM collection
 */
public class Blob extends AType {

	public static final Blob INSTANCE = new Blob();
	
	private Blob() {
		
	}

	@Override
	public boolean check(ACell value) {
		return (value instanceof ABlob);
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
	protected ABlob defaultValue() {
		return convex.core.data.Blob.EMPTY;
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
