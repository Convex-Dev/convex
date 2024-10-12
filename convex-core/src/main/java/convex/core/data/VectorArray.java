package convex.core.data;

import java.util.ListIterator;
import java.util.NoSuchElementException;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import convex.core.exceptions.InvalidDataException;
import convex.core.lang.RT;
import convex.core.util.ErrorMessages;
import convex.core.util.Utils;

/**
 * Non-canonical vector implementation designed to make operations on small temporary vectors more efficient.
 * 
 * Directly wraps an array of cells, considered effectively immutable
 * 
 * @param <T> Type of vector elements
 */
public class VectorArray<T extends ACell> extends ASpecialVector<T> {

	private ACell[] data;
	private int start;
	
	private VectorArray(ACell[] data, long start, long count) {
		super(count);
		this.data=data;
		this.start=Utils.checkedInt(start);
	}
	
	public static <T extends ACell> VectorArray<T> wrap(ACell[] arr) {
		return new VectorArray<T>(arr,0,arr.length);
	}

	public static <T extends ACell> VectorArray<T> of(Object ... os) {
		int n=os.length;
		ACell[] data = new ACell[n];
		for (int i=0; i<n; i++) {
			data[i]=RT.cvm(os[i]);
		}
		return new VectorArray<T>(data,0,n);
	}

	private class VectorArrayIterator implements ListIterator<T> {
		long pos=0;

		public VectorArrayIterator(long pos) {
			this.pos=pos;
		}

		@Override
		public boolean hasNext() {
			return (pos<count);
		}

		@Override
		public T next() {
			if (pos>=count) throw new NoSuchElementException();
			return get(pos++);
		}

		@Override
		public boolean hasPrevious() {
			return (pos>0);
		}

		@Override
		public T previous() {
			if (pos > 0) return get(--pos);
			throw new NoSuchElementException();
		}

		@Override
		public int nextIndex() {
			return Utils.checkedInt(pos);
		}

		@Override
		public int previousIndex() {
			return Utils.checkedInt(pos)-1;
		}

		@Override
		public void remove() {
			throw new UnsupportedOperationException(ErrorMessages.immutable(this));
		}

		@Override
		public void set(T e) {
			throw new UnsupportedOperationException(ErrorMessages.immutable(this));
		}

		@Override
		public void add(T e) {
			throw new UnsupportedOperationException(ErrorMessages.immutable(this));
		}
	}
	
	@Override
	public ListIterator<T> listIterator() {
		return new VectorArrayIterator(0);
	}

	@Override
	public int estimatedEncodingSize() {
		return getCanonical().estimatedEncodingSize();
	}

	@SuppressWarnings("unchecked")
	@Override
	public T get(long i) {
		checkIndex(i);
		return (T) data[Utils.checkedInt(start+i)];
	}

	@Override
	public AVector<T> appendChunk(AVector<T> listVector) {
		return toVector().appendChunk(listVector);
	}

	@Override
	public VectorLeaf<T> getChunk(long offset) {
		return toVector().getChunk(offset);
	}

	@Override
	public AVector<T> append(T value) {
		return toVector().append(value);
	}

	@Override
	public boolean isFullyPacked() {
		return toVector().isFullyPacked();
	}

	@Override
	public boolean anyMatch(Predicate<? super T> pred) {
		return toVector().anyMatch(pred);
	}

	@Override
	public boolean allMatch(Predicate<? super T> pred) {
		return toVector().allMatch(pred);
	}

	@Override
	public <R extends ACell> AVector<R> map(Function<? super T, ? extends R> mapper) {
		return toVector().map(mapper);
	}

	@Override
	public AVector<T> concat(ASequence<? extends T> b) {
		return toVector().concat(b);
	}

	@Override
	public <R> R reduce(BiFunction<? super R, ? super T, ? extends R> func, R value) {
		return toVector().reduce(func, value);
	}

	@Override
	public ListIterator<T> listIterator(long index) {
		if ((index<0)||(index>count)) throw new IndexOutOfBoundsException(index); 
		return new VectorArrayIterator(index);
	}

	@Override
	public boolean isCanonical() {
		return false;
	}

	@Override
	public AVector<T> updateRefs(IRefFunction func) {
		return toVector().updateRefs(func);
	}

	@Override
	public long commonPrefixLength(AVector<T> b) {
		return toVector().commonPrefixLength(b);
	}

	@Override
	public AVector<T> next() {
		if (count <= 1) return null;
		return new VectorArray<T>(data,start+1,count-1);
	}

	@Override
	public AVector<T> assoc(long i, T value) {
		return toVector().assoc(i,value);
	}

	@Override
	public int encodeRaw(byte[] bs, int pos) {
		return toVector().encodeRaw(bs, pos);
	}

	@Override
	protected Ref<T> getElementRefUnsafe(long i) {
		return toVector().getElementRefUnsafe(i);
	}

	@Override
	public long longIndexOf(ACell value) {
		for (int i=0; i<count; i++) {
			if (Utils.equals(data[start+i],value)) return i;
		}
		return -1;
	}

	@Override
	public long longLastIndexOf(ACell value) {
		for (int i=Utils.checkedInt(count)-1; i>=0; i--) {
			if (Utils.equals(data[start+i],value)) return i;
		}
		return -1;
	}

	@SuppressWarnings("unchecked")
	@Override
	public void forEach(Consumer<? super T> action) {
		for (int i=0; i<count; i++) {
			action.accept((T)data[start+i]);
		}
	}

	@SuppressWarnings("unchecked")
	@Override
	public void visitElementRefs(Consumer<Ref<T>> f) {
		for (int i=0; i<count; i++) {
			f.accept((Ref<T>) Ref.get(data[start+i]));
		}
	}

	@Override
	public Ref<T> getElementRef(long index) {
		return Ref.get(get(index));
	}

	@Override
	public AVector<T> slice(long start, long end) {
		if (!checkRange(start,end)) return null;
		long length=end-start;
		if (length==0) return Vectors.empty();
		if (length==count) return this;
		return new VectorArray<T>(data,this.start+start,length);
	}

	@Override
	public int encode(byte[] bs, int pos) {
		return getCanonical().encode(bs, pos);
	}

	@SuppressWarnings("unchecked")
	@Override
	public AVector<T> toVector() {
		return (AVector<T>)getCanonical();
	}

	@SuppressWarnings("unchecked")
	@Override
	protected <R> void copyToArray(R[] arr, int offset) {
		for (int i=0; i<count; i++) {
			arr[offset+i]=(R) data[start+i];
		}
	}

	@Override
	public void validateCell() throws InvalidDataException {
		getCanonical().validateCell();
	}

	@Override
	public boolean equals(AVector<? super T> a) {
		if (a==this) return true;
		if (a==null) return false;
		if (this.count!=a.count) return false;
 		return getCanonical().equals(a);
	}

	@Override
	protected ACell toCanonical() {
		return 	Vectors.create(data, Utils.checkedInt(start), Utils.checkedInt(count));
	}

	@Override
	public int getRefCount() {
		return getCanonical().getRefCount();
	}
	
	@Override
	public Ref<ACell> getRef(int i) {
		return getCanonical().getRef(i);
	}





}
