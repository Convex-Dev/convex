package convex.core.data.type;

import convex.core.data.ABlob;
import convex.core.data.ACell;

/**
 * Type that represents any Blob
 */
public class Blob extends AStandardType<ABlob> {

	public static final Blob INSTANCE = new Blob();
		
	private Blob() {
		super(ABlob.class);
	}

	@Override
	public boolean check(ACell value) {
		return (value instanceof ABlob);
	}

	@Override
	public String toString() {
		return "Blob";
	}

	@Override
	public ABlob defaultValue() {
		return convex.core.data.Blob.SINGLE_A;
	}

	@Override
	public ABlob implicitCast(ACell a) {
		if (a instanceof ABlob) return (ABlob)a;
		return null;
	}


}
