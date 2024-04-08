package convex.core.data.type;

import convex.core.data.ACell;
import convex.core.data.AIndex;
import convex.core.data.Index;

/**
 * Type that represents any CVM map
 */
@SuppressWarnings("rawtypes")
public class IndexType extends AStandardType<AIndex> {

	public static final IndexType INSTANCE = new IndexType();
	
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

}
