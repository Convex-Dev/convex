package convex.core.data.type;

import convex.core.data.ACell;
import convex.core.lang.RT;
import convex.core.data.AMap;
import convex.core.data.Maps;

/**
 * Type that represents any CVM map
 */
@SuppressWarnings("rawtypes")
public class Map extends AStandardType<AMap> {

	public static final Map INSTANCE = new Map();
	
	private Map() {
		super(AMap.class);
	}

	@Override
	public boolean check(ACell value) {
		return RT.ensureMap(value)!=null;
	}

	@Override
	public String toString() {
		return "Map";
	}

	@Override
	public AMap<?,?> defaultValue() {
		return Maps.empty();
	}

	@Override
	public AMap implicitCast(ACell a) {
		return RT.ensureMap(a);
	}

}
