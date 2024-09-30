package convex.core.data.type;

import convex.core.data.ACell;
import convex.core.data.AIndex;
import convex.core.data.AString;
import convex.core.data.Index;
import convex.core.data.Strings;

/**
 * Type that represents any CVM map
 */
@SuppressWarnings("rawtypes")
public class IndexType extends AStandardType<AIndex> {

	public static final IndexType INSTANCE = new IndexType();
	
	private static final AString TAG=Strings.create("#Index");
	
	private IndexType() {
		super(AIndex.class);
	}

	@Override
	public boolean check(ACell value) {
		return (value instanceof AIndex);
	}

	@Override
	public String toString() {
		return "Index";
	}

	@Override
	public AIndex<?,?> defaultValue() {
		return Index.none();
	}

	@Override
	public AIndex implicitCast(ACell a) {
		if (a instanceof Index) return (Index)a;
		return null;
	}
	
	@Override
	public AString getTag() {
		return TAG;
	}

}
