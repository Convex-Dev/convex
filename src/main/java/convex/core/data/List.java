package convex.core.data;

import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.ListIterator;
import java.util.function.Consumer;
import java.util.function.Function;

import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.util.Errors;
import convex.core.util.Utils;

/**
 * Implementation of a list wrapping a vector.
 * 
 * Note that we embed the vector directly, avoiding going via a Ref. This is
 * important for serialisation efficiency / avoiding excess Cells.
 * 
 * "One can even conjecture that Lisp owes its survival specifically to the fact
 * that its programs are lists, which everyone, including me, has regarded as a
 * disadvantage." - John McCarthy, "Early History of Lisp"
 * 
 * @param <T>
 */
public class List<T> extends AList<T> {

	static final List<Object> EMPTY = new List<>(Vectors.empty());

	AVector<T> data;
	private long count;

	private List(AVector<T> data) {
		this.data = data.toVector(); // ensure canonical, not a mapentry etc.
		this.count = data.count();
	}

	/**
	 * Creates a List containing the elements of the provided vector in reverse
	 * order
	 * 
	 * @param <T>    Type of list elements
	 * @param vector
	 * @return Vector representing this list in reverse order
	 */
	@SuppressWarnings("unchecked")
	public static <T> List<T> reverse(AVector<T> vector) {
		if (vector.isEmpty()) return (List<T>) Lists.empty();
		return new List<T>(vector);
	}

	@SuppressWarnings("unchecked")
	public static <T> List<T> of(T... elements) {
		if (elements.length == 0) return (List<T>) EMPTY;
		Utils.reverse(elements);
		return new List<T>(Vectors.create(elements));
	}

	@Override
	public Object[] toArray() {
		Object[] arr = data.toArray();
		Utils.reverse(arr, size());
		return arr;
	}

	@Override
	public <V> V[] toArray(V[] a) {
		V[] arr = data.toArray(a);
		Utils.reverse(arr, size());
		return arr;
	}

	@Override
	public T get(long index) {
		return data.get(count - 1 - index);
	}

	@Override
	protected Ref<T> getElementRef(long i) {
		return data.getElementRef(count - 1 - i);
	}

	@Override
	public AList<T> assoc(long i, T value) {
		AVector<T> newData;
		newData = data.assoc(count - 1 - i, value);
		if (data == newData) return this;
		return new List<>(newData);
	}

	@Override
	public int indexOf(Object o) {
		int pos = data.lastIndexOf(o);
		if (pos < 0) return -1;
		return size() - 1 - pos;
	}

	@Override
	public int lastIndexOf(Object o) {
		int pos = data.indexOf(o);
		if (pos < 0) return -1;
		return size() - 1 - pos;
	}

	@Override
	public long longIndexOf(Object o) {
		long pos = data.longLastIndexOf(o);
		if (pos < 0) return -1;
		return count - 1 - pos;
	}

	@Override
	public long longLastIndexOf(Object o) {
		long pos = data.longIndexOf(o);
		if (pos < 0) return -1;
		return count - 1 - pos;
	}

	@Override
	public ListIterator<T> listIterator() {
		return new MyListIterator(0);
	}

	@Override
	public ListIterator<T> listIterator(int index) {
		return new MyListIterator(index);
	}

	@Override
	public ListIterator<T> listIterator(long index) {
		return new MyListIterator(index);
	}

	private class MyListIterator implements ListIterator<T> {

		private final ListIterator<T> dataIterator;

		public MyListIterator(long pos) {
			this.dataIterator = data.listIterator(count - pos);
		}

		@Override
		public boolean hasNext() {
			return dataIterator.hasPrevious();
		}

		@Override
		public T next() {
			return dataIterator.previous();
		}

		@Override
		public boolean hasPrevious() {
			return dataIterator.hasNext();
		}

		@Override
		public T previous() {
			return dataIterator.next();
		}

		@Override
		public int nextIndex() {
			return Utils.checkedInt(count - 1 - dataIterator.previousIndex());
		}

		@Override
		public int previousIndex() {
			return Utils.checkedInt(count - 1 - dataIterator.nextIndex());
		}

		@Override
		public void remove() {
			throw new UnsupportedOperationException(Errors.immutable(this));
		}

		@Override
		public void set(T e) {
			throw new UnsupportedOperationException(Errors.immutable(this));
		}

		@Override
		public void add(T e) {
			throw new UnsupportedOperationException(Errors.immutable(this));
		}
	}

	@Override
	public int getRefCount() {
		return data.getRefCount();
	}

	@Override
	public <R> Ref<R> getRef(int i) {
		return data.getRef(i);
	}

	@Override
	public List<T> updateRefs(IRefFunction func) {
		AVector<T> newData = (AVector<T>) data.updateRefs(func);
		if (newData == data) return this;
		return new List<T>(newData);
	}

	@Override
	public boolean isCanonical() {
		return true;
	}

	@Override
	public void ednString(StringBuilder sb) {
		sb.append('(');
		long n = count;
		for (long i = 0; i < n; i++) {
			if (i > 0) sb.append(' ');
			Utils.ednString(sb,data.get(n - 1 - i));
		}
		sb.append(')');
	}
	
	@Override
	public void print(StringBuilder sb) {
		sb.append('(');
		long n = count;
		for (long i = 0; i < n; i++) {
			if (i > 0) sb.append(' ');
			Utils.print(sb,data.get(n - 1 - i));
		}
		sb.append(')');
	}

	@Override
	public ByteBuffer write(ByteBuffer bb) {
		bb = bb.put(Tag.LIST);
		return writeRaw(bb);
	}

	@Override
	public ByteBuffer writeRaw(ByteBuffer bb) {
		bb = data.writeRaw(bb);
		return bb;
	}
	
	/**
	 * Reads a List from the specified bytebuffer. Assumes Tag byte already consumed.
	 * 
	 */
	public static <T> List<T> read(ByteBuffer bb) throws BadFormatException {
		try {
			AVector<T> data = Vectors.read(bb);
			if (data == null) throw new BadFormatException("Expected vector but got null in List format");
			return new List<T>(data);
		} catch (ClassCastException e) {
			throw new BadFormatException("Expected vector in List format", e);
		}
	}

	@Override
	public long count() {
		return count;
	}

	@Override
	public Iterator<T> iterator() {
		return listIterator();
	}

	@Override
	public int estimatedEncodingSize() {
		return 50;
	}

	/**
	 * Prepends an element to the list in first position.
	 * 
	 * @param value
	 * @return Updated list
	 */
	@Override
	public <R> List<R> conj(R value) {
		return new List<R>((AVector<R>) data.conj(value));
	}
	
	@Override
	public AList<T> cons(T x) {
		return new List<T>((AVector<T>) data.conj(x));
	}


	@SuppressWarnings("unchecked")
	@Override
	public AVector<T> toVector() {
		return (AVector<T>) Vectors.create(toArray());
	}

	@Override
	public ASequence<T> next() {
		if (count <= 1) return null;
		return slice(1, count - 1);
	}

	@Override
	public ASequence<T> slice(long start, long length) {
		long end = start + length;
		if ((start == 0) && (end == count)) return this;
		return reverse(data.slice(count - end, length));
	}

	@Override
	public <R> AList<R> map(Function<? super T, ? extends R> mapper) {
		// TODO: reverse map order?
		return new List<>(data.map(mapper));
	}

	@Override
	public AList<T> concat(ASequence<T> vals) {
		AVector<T> rvals;
		if (vals instanceof List) {
			rvals = ((List<T>) vals).data;
		} else {
			rvals = Vectors.empty();
			long n = vals.count();
			for (long i = 0; i < n; i++) {
				rvals = rvals.conj(vals.get(n - i - 1));
			}
		}
		return List.reverse(rvals.concat(data));
	}

	@Override
	public void visitElementRefs(Consumer<Ref<T>> f) {
		data.visitElementRefs(f);
	}

	@SuppressWarnings("unchecked")
	@Override
	public AVector<T> subVector(long start, long length) {
		checkRange(start, length);

		// Create using an Object array. Probably fastest?
		Object[] arr = new Object[Utils.checkedInt(length)];
		for (int i = 0; i < length; i++) {
			arr[i] = get(start + i);
		}
		return (AVector<T>) Vectors.of(arr);
	}

	@Override
	public void validateCell() throws InvalidDataException {
		long dc = data.count();
		if (count != dc)
			throw new InvalidDataException("Bad data count " + count + " with underlying data count " + dc, this);
		data.validateCell();
	}

	@Override
	public void forEach(Consumer<? super T> action) {
		data.forEach(action);
	}

	@Override
	public AList<T> drop(long n) {
		if (n==0) return this;
		long newLen=count-n;
		if (newLen<0) return null;
		if (newLen==0) return Lists.empty();
		return new List<T>(data.subVector(0, newLen));
	}
}
