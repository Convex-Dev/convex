package convex.core.data;

public class Lists {

	@SuppressWarnings("unchecked")
	public static <T, L extends AList<T>> L create(java.util.List<T> list) {
		return (L) List.of(list.toArray());
	}

	@SafeVarargs
	public static <T> AList<T> of(Object... vals) {
		return List.of(vals);
	}

	@SuppressWarnings("unchecked")
	public static <T> AList<T> empty() {
		return (List<T>) List.EMPTY;
	}

}
