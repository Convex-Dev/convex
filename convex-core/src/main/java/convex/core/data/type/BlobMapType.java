package convex.core.data.type;

import convex.core.data.ABlobMap;
import convex.core.data.ACell;
import convex.core.data.BlobMap;
import convex.core.data.BlobMaps;

/**
 * Type that represents any CVM map
 */
@SuppressWarnings("rawtypes")
public class BlobMapType extends AStandardType<ABlobMap> {

	public static final BlobMapType INSTANCE = new BlobMapType();
	
	private BlobMapType() {
		super(ABlobMap.class);
	}

	@Override
	public boolean check(ACell value) {
		return (value instanceof ABlobMap);
	}

	@Override
	public String toString() {
		return "BlobMap";
	}

	@Override
	public ABlobMap<?,?> defaultValue() {
		return BlobMaps.empty();
	}

	@Override
	public ABlobMap implicitCast(ACell a) {
		if (a instanceof BlobMap) return (BlobMap)a;
		return null;
	}

}
