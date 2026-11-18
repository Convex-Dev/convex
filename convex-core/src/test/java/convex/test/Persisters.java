package convex.test;

import java.io.IOException;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import convex.core.data.ACell;
import convex.core.store.Persister;

/**
 * Persisters for tests: ones that write nothing, fail, run a hook first, or stand in
 * for a store with a function on one value type.
 */
public final class Persisters {

	private Persisters() {}

	/** Returns each value as given and writes nothing. */
	public static final Persister UNCHANGED=new Persister() {
		@Override
		public <T extends ACell> T persist(T value) {
			return value;
		}
	};

	/** Runs the hook on each value, then persists it with the persister given. */
	public static Persister after(Consumer<ACell> hook,Persister persister) {
		return new Persister() {
			@Override
			public <T extends ACell> T persist(T value) throws IOException {
				hook.accept(value);
				return persister.persist(value);
			}
		};
	}

	/** Fails every persist with the exception given. */
	public static Persister failing(IOException failure) {
		return new Persister() {
			@Override
			public <T extends ACell> T persist(T value) throws IOException {
				throw failure;
			}
		};
	}

	/**
	 * Replaces each value with the function's result, such as an equal copy in new
	 * cells. Every value persisted must be of the function's type.
	 */
	public static <V extends ACell> Persister replacing(UnaryOperator<V> function) {
		return new Persister() {
			@SuppressWarnings("unchecked")
			@Override
			public <T extends ACell> T persist(T value) {
				return (T)function.apply((V)value);
			}
		};
	}
}
