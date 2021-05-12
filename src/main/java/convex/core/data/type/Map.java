package convex.core.data.type;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.ARecord;
import convex.core.data.Maps;

/**
 * Type that represents any CVM collection
 */
public class Map extends AType {

	public static final Map INSTANCE = new Map();
	
	private Map() {
		
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
		return "Map";
	}

	@Override
	protected AMap<?,?> defaultValue() {
		return Maps.empty();
	}

	@SuppressWarnings("rawtypes")
	@Override
	protected AMap implicitCast(ACell a) {
		if (a instanceof AMap) return (AMap)a;
		return null;
	}
	
	@Override
	protected Class<? extends ACell> getJavaClass() {
		return AMap.class;
	}

}
