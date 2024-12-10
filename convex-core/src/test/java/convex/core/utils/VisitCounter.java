package convex.core.utils;

import java.util.function.Consumer;

public class VisitCounter<T> implements Consumer<T> {

	public long count;
	
	@Override
	public void accept(T t) {
		count++;
	}

}
