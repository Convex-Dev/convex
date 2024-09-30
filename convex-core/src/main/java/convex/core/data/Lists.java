package convex.core.data;

public class Lists {
	
	@SuppressWarnings("unchecked")
	public static <T extends ACell> List<T> empty() {
		return (List<T>) List.EMPTY;
	}

	@SuppressWarnings("unchecked")
	public static <T extends ACell, L extends AList<T>> L create(java.util.List<T> list) {
		return (L) List.create(Cells.toCellArray(list));
	}

	@SafeVarargs
	public static <T extends ACell> AList<T> of(Object... vals) {
		return List.of(vals);
	}
}
