package convex.core.data;

import java.util.AbstractSet;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Predicate;

import convex.core.util.ErrorMessages;

/**
 * Lightweight immutable {@link Set} view over the entries of an {@link AIndex}.
 *
 * @param <K> Type of Index keys
 * @param <V> Type of Index values
 */
final class IndexEntrySet<K extends ABlobLike<?>, V extends ACell> extends AbstractSet<Map.Entry<K, V>> {

	private final AIndex<K, V> index;

	IndexEntrySet(AIndex<K, V> index) {
		this.index = Objects.requireNonNull(index);
	}

	@Override
	public Iterator<Map.Entry<K, V>> iterator() {
		return new Iterator<>() {
			private long position;

			@Override
			public boolean hasNext() {
				return position < index.count();
			}

			@Override
			public Map.Entry<K, V> next() {
				if (!hasNext()) throw new NoSuchElementException();
				return index.entryAt(position++);
			}

			@Override
			public void remove() {
				throw immutable();
			}
		};
	}

	@Override
	public Spliterator<Map.Entry<K, V>> spliterator() {
		int characteristics = Spliterator.DISTINCT | Spliterator.IMMUTABLE | Spliterator.NONNULL
				| Spliterator.ORDERED;
		return Spliterators.spliterator(iterator(), index.count(), characteristics);
	}

	@Override
	public int size() {
		return index.size();
	}

	@Override
	public boolean isEmpty() {
		return index.isEmpty();
	}

	@Override
	public boolean contains(Object o) {
		if (!(o instanceof Map.Entry<?, ?> entry)) return false;
		if (!(entry.getKey() instanceof ACell key)) return false;

		MapEntry<K, V> candidate = index.getEntry(key);
		if (candidate == null) return false;

		Object value = entry.getValue();
		if ((value != null) && !(value instanceof ACell)) return false;
		return candidate.getValueRef().equals(Ref.get((ACell) value));
	}

	@Override
	public boolean add(Map.Entry<K, V> e) {
		throw immutable();
	}

	@Override
	public boolean remove(Object o) {
		throw immutable();
	}

	@Override
	public boolean addAll(Collection<? extends Map.Entry<K, V>> c) {
		throw immutable();
	}

	@Override
	public boolean retainAll(Collection<?> c) {
		throw immutable();
	}

	@Override
	public boolean removeAll(Collection<?> c) {
		throw immutable();
	}

	@Override
	public boolean removeIf(Predicate<? super Map.Entry<K, V>> filter) {
		throw immutable();
	}

	@Override
	public void clear() {
		throw immutable();
	}

	private UnsupportedOperationException immutable() {
		return new UnsupportedOperationException(ErrorMessages.immutable(index));
	}
}
