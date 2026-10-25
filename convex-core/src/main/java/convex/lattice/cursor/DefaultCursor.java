package convex.lattice.cursor;

import java.util.Objects;

import convex.core.data.ACell;

/**
 * An explicit default for absent storage. Reads and atomic update functions see
 * the supplied default in place of null. Reading or writing back that default
 * leaves absent storage absent; a changed value is written normally. Writing null
 * clears storage, after which reads again return the default.
 *
 * <p>The default is a value chosen by the caller, not an inferred container type
 * or lattice policy. Descendant paths can therefore edit an absent container
 * without creating it until an actual change occurs.</p>
 *
 * @param <V> Value type
 */
public final class DefaultCursor<V extends ACell> extends AUpdateCursor<V,V> {

	private final V defaultValue;

	public DefaultCursor(ACursor<V> base,V defaultValue) {
		super(Objects.requireNonNull(base));
		this.defaultValue=Objects.requireNonNull(defaultValue);
	}

	@Override protected V view(V stored) { return stored==null?defaultValue:stored; }
	@Override protected V prepareWrite(V value) { return value; }
}
