package convex.core.data;

import java.util.Iterator;
import java.util.ListIterator;
import java.util.function.Consumer;
import java.util.function.Function;

import convex.core.data.util.BlobBuilder;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.RT;
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
 * @param <T> Type of List elements
 */
public class List<T extends ACell> extends AList<T> {
	
	public static final List<?> EMPTY = Cells.intern(wrap(VectorLeaf.EMPTY));

	public static final Ref<List<?>> EMPTY_REF = EMPTY.getRef();

	/**
	 * Wrapped vector containing reversed elements
	 */
	private AVector<T> data;

	private List(AVector<T> data) {
		super(data.count);
		this.data = (AVector<T>) data.toVector(); // ensure canonical, not a mapentry etc.
	}
	
	/**
	 * Wraps a Vector as a list (will reverse element order)
	 * @param <R> Type of elements
	 * @param vector Vector to wrap
	 * @return New List instance
	 */
	public static <R extends ACell> List<R> wrap(AVector<R> vector) {
		return new List<R>(vector);
	}

	/**
	 * Creates a List containing the elements of the provided vector in reverse
	 * order
	 * 
	 * @param <T>    Type of list elements
	 * @param vector Vector to reverse into a List
	 * @return Vector representing this list in reverse order
	 */
	@SuppressWarnings("unchecked")
	public static <T extends ACell> List<T> reverse(AVector<T> vector) {
		if (vector.isEmpty()) return (List<T>) Lists.empty();
		return new List<T>(vector);
	}

	@SuppressWarnings("unchecked")
	public static <T extends ACell> List<T> of(Object... elements) {
		if (elements.length == 0) return (List<T>) Lists.empty();
		Utils.reverse(elements);
		return new List<T>(Vectors.of(elements));
	}
	
	/***
	 * Creates a list wrapping the given array. May destructively alter the array
	 * @param <T> Type of element
	 * @param args Elements to include
	 * @return New List
	 */
	@SuppressWarnings("unchecked")
	public static <T extends ACell> List<T> create(ACell... args) {
		if (args.length==0) return (List<T>) Lists.empty();
		Utils.reverse(args);
		return new List<T>(Vectors.create(args));
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
	public Ref<T> getElementRef(long i) {
		return data.getElementRef(count - 1 - i);
	}

	@Override
	public AList<T> assoc(long i, T value) {
		AVector<T> newData;
		newData = data.assoc(count - 1 - i, value);
		if (data == newData) return this;
		if (newData==null) return null;
		return new List<T>(newData);
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
	public <R extends ACell> Ref<R> getRef(int i) {
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
	public boolean print(BlobBuilder bb, long limit) {
		bb.append('(');
		long n = count;
		for (long i = 0; i < n; i++) {
			if (i > 0) bb.append(' ');
			if (!RT.print(bb,data.get(n - 1 - i),limit)) return false;;
		}
		bb.append(')');
		return bb.check(limit);
	}

	@Override
	public int encode(byte[] bs, int pos) {
		bs[pos++]=Tag.LIST;
		return encodeRaw(bs,pos);
	}

	@Override
	public int encodeRaw(byte[] bs, int pos) {
		pos = data.encodeRaw(bs,pos);
		return pos;
	}
	
	/**
	 * Reads a List from the specified Blob. 
	 * @param b Blob to read from
	 * @param pos Position to read from (must point at tag, assumed to be a List)
	 * 
	 * @return List instance 
	 * @throws BadFormatException If Encoding is invalid
	 * 
	 */
	@SuppressWarnings("unchecked")
	public static <T extends ACell> List<T> read(Blob b, int pos) throws BadFormatException {
		try {
			AVector<T> data = Vectors.read(b,pos);
			if (data.isEmpty()) return (List<T>) EMPTY;
			List<T> result=new List<T>(data);
			result.attachEncoding(data.cachedEncoding()); // keep acquired encoding
			data.attachEncoding(null); // invalidate encoding since we have a List tag
			return result;
		} catch (ClassCastException e) {
			throw new BadFormatException("Expected vector in List format", e);
		}
	}

	@Override
	public Iterator<T> iterator() {
		return listIterator();
	}

	@Override
	public int estimatedEncodingSize() {
		return data.estimatedEncodingSize();
	}

	/**
	 * Prepends an element to the list in first position.
	 * 
	 * @param value Value to prepend
	 * @return Updated list
	 */
	@Override
	public List<T> conj(ACell value) {
		return new List<T>(data.conj(value));
	}
	
	@Override
	public AList<T> cons(T x) {
		return new List<T>((AVector<T>) data.conj(x));
	}
	
	public List<T> conjAll(ACollection<? extends T> xs) {
		return reverse(data.conjAll(xs));
	}


	@SuppressWarnings("unchecked")
	@Override
	public AVector<T> toVector() {
		return (AVector<T>) Vectors.create(toCellArray());
	}

	@Override
	public AList<T> next() {
		if (count <= 1) return null;
		return slice(1, count);
	}

	@Override
	public AList<T> slice(long start, long end) {
		if ((start == 0) && (end == count)) return this;
		return reverse(data.slice(count - end, count-start));
	}

	@SuppressWarnings("unchecked")
	@Override
	public <R extends ACell> AList<R> map(Function<? super T, ? extends R> mapper) {
		AVector<R> newData=data.map(mapper);
		if (data==newData) return (AList<R>) this;
		return new List<>(newData);
	}

	@SuppressWarnings("unchecked")
	@Override
	public List<T> concat(ASequence<? extends T> vals) {
		long n = RT.count(vals);
		if (n==0) return this;

		AVector<T> rvals;
		if (vals instanceof List) {
			List<T> vlist=(List<T>) vals;
			if (count==0) return vlist;
			rvals = vlist.data;
		} else {
			rvals = Vectors.empty();
			for (long i = 0; i < n; i++) {
				rvals = rvals.conj(vals.get(n - i - 1));
			}
		}
		if (count>0) {
			rvals=rvals.concat(data);
		}
		return List.reverse(rvals);
	}

	@Override
	public void visitElementRefs(Consumer<Ref<T>> f) {
		data.visitElementRefs(f);
	}

	@Override
	public AVector<T> subVector(long start, long length) {
		if (!checkRange(start, start+length)) return null;
		if (length==0) return Vectors.empty();

		// Create using an Object array. Probably fastest?
		ACell[] arr = new ACell[Utils.checkedInt(length)];
		for (int i = 0; i < length; i++) {
			arr[i] = get(start + i);
		}
		return Vectors.wrap(arr);
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
		return new List<T>(data.slice(0, newLen));
	}

	@Override
	public byte getTag() {
		return Tag.LIST;
	}


	@Override
	public AVector<T> reverse() {
		return data;
	}

	@SuppressWarnings("unchecked")
	@Override
	protected <R> void copyToArray(R[] arr, int offset) {
		int n=Utils.checkedInt(count);
		for (int i=0; i<n; i++) {
			arr[offset+i]=(R)get(offset+i);
		}
	}

	@Override
	public ACell toCanonical() {
		return this;
	}

	@Override
	public boolean equals(ACell o) {
		if (!(o instanceof List)) return false;
		if (this==o) return true;
		@SuppressWarnings("unchecked")
		List<T> b=(List<T>)o;
		return data.equals(b.data);
	}


}
