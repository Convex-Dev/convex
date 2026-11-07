package convex.core.data;

import java.util.ListIterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import convex.core.exceptions.InvalidDataException;
import convex.core.util.ErrorMessages;
import convex.core.util.Utils;

/**
 * A path of string segments addressing a lattice component, as a caller writes it.
 * A component reads its segments, then hands the rest to a child with
 * {@link #slice(long)}. Slices share the segments of the request they came from,
 * so passing a path on copies nothing, and {@link #position()} still locates a slice
 * in that request.
 *
 * <p>This is a non-canonical vector of strings for in-memory use, like
 * {@link VectorArray}, specialised to strings and final so that call sites see one
 * type. It equals any vector holding the same strings, whatever its position, and
 * encodes, hashes, validates and stores as that canonical vector, which is built when
 * first needed.</p>
 */
public final class Path extends ASpecialVector<AString> {

	/** Segments of the whole request, never modified after construction. */
	private final AString[] segments;

	/** Position of this path's first segment in the request. */
	private final int start;

	private Path(AString[] segments,int start,long count) {
		super(count);
		this.segments=segments;
		this.start=start;
	}

	/**
	 * Creates a path from the given segments, which are copied.
	 *
	 * @param segments Path segments, as the caller writes them
	 * @return Path of those segments, at position 0 of its own request
	 * @throws NullPointerException If a segment is null
	 */
	public static Path of(AString... segments) {
		AString[] copy=segments.clone();
		for (AString segment:copy) Objects.requireNonNull(segment,"Path segment must not be null");
		return new Path(copy,0,copy.length);
	}

	/** Position of this path's first segment in the request it was sliced from. */
	public int position() {
		return start;
	}

	/** The whole request this path was sliced from. */
	public Path request() {
		if (start==0 && count==segments.length) return this;
		return new Path(segments,0,segments.length);
	}

	@Override
	public AString get(long i) {
		checkIndex(i);
		return segments[start+(int)i];
	}

	@Override
	public Path slice(long start) {
		return slice(start,count);
	}

	/** Slices this path, sharing its request. An empty slice keeps its position. */
	@Override
	public Path slice(long start,long end) {
		if (!checkRange(start,end)) return null;
		if (start==0 && end==count) return this;
		return new Path(segments,this.start+(int)start,end-start);
	}

	@Override
	public Path next() {
		if (count<=1) return null;
		return slice(1);
	}

	/**
	 * Appends a segment, keeping a path. The result is a new request, at position 0.
	 * A value not typed as a string goes to {@link #conj(ACell)} instead, which gives
	 * the canonical vector.
	 *
	 * @param segment Segment to append
	 * @return Path of this path's segments followed by the segment
	 * @throws NullPointerException If the segment is null
	 */
	public Path conj(AString segment) {
		Objects.requireNonNull(segment,"Path segment must not be null");
		int n=Utils.checkedInt(count);
		AString[] joined=new AString[n+1];
		System.arraycopy(segments,start,joined,0,n);
		joined[n]=segment;
		return new Path(joined,0,n+1);
	}

	/**
	 * Appends the segments of another path, keeping a path. The result is a new
	 * request, at position 0.
	 *
	 * @param rest Path whose segments follow this path's
	 * @return Path of this path's segments followed by those of rest
	 */
	public Path concat(Path rest) {
		int n=Utils.checkedInt(count);
		int m=Utils.checkedInt(rest.count);
		AString[] joined=new AString[n+m];
		System.arraycopy(segments,start,joined,0,n);
		System.arraycopy(rest.segments,rest.start,joined,n,m);
		return new Path(joined,0,n+m);
	}

	@Override
	public boolean equals(AVector<?> a) {
		if (a==this) return true;
		if (a==null) return false;
		if (count!=a.count) return false;
		if (a instanceof Path p) {
			for (int i=0; i<count; i++) {
				if (!segments[start+i].equals(p.segments[p.start+i])) return false;
			}
			return true;
		}
		return getCanonical().equals(a);
	}

	@Override
	public boolean isCanonical() {
		return false;
	}

	@Override
	protected AVector<AString> toCanonical() {
		return Vectors.create(segments,start,Utils.checkedInt(count));
	}

	@Override
	public AVector<AString> toVector() {
		return getCanonical();
	}

	@Override
	public int encode(byte[] bs,int pos) {
		return getCanonical().encode(bs,pos);
	}

	@Override
	public int encodeRaw(byte[] bs,int pos) {
		return toVector().encodeRaw(bs,pos);
	}

	@Override
	public int calcHeaderLength() {
		// Non-canonical: the canonical vector encodes
		return getCanonical().calcHeaderLength();
	}

	@Override
	public void validateCell() throws InvalidDataException {
		getCanonical().validateCell();
	}

	@Override
	public int getRefCount() {
		return getCanonical().getRefCount();
	}

	@Override
	public Ref<ACell> getRef(int i) {
		return getCanonical().getRef(i);
	}

	@Override
	protected AVector<AString> rebuildRefs(IRefFunction func) {
		return toVector().updateRefs(func);
	}

	@Override
	protected Ref<AString> getElementRefUnsafe(long i) {
		return toVector().getElementRefUnsafe(i);
	}

	@Override
	public Ref<AString> getElementRef(long index) {
		return Ref.get(get(index));
	}

	@Override
	public void visitElementRefs(Consumer<Ref<AString>> f) {
		for (int i=0; i<count; i++) f.accept(Ref.get(segments[start+i]));
	}

	@Override
	public void forEach(Consumer<? super AString> action) {
		for (int i=0; i<count; i++) action.accept(segments[start+i]);
	}

	@Override
	public long longIndexOf(ACell value) {
		for (int i=0; i<count; i++) {
			if (Utils.equals(segments[start+i],value)) return i;
		}
		return -1;
	}

	@Override
	public long longLastIndexOf(ACell value) {
		for (int i=Utils.checkedInt(count)-1; i>=0; i--) {
			if (Utils.equals(segments[start+i],value)) return i;
		}
		return -1;
	}

	@SuppressWarnings("unchecked")
	@Override
	protected <R> void copyToArray(R[] arr,int offset) {
		for (int i=0; i<count; i++) arr[offset+i]=(R)segments[start+i];
	}

	@Override
	public long commonPrefixLength(AVector<AString> b) {
		return toVector().commonPrefixLength(b);
	}

	@Override
	public AVector<AString> dissocAt(long i) {
		return toVector().dissocAt(i);
	}

	@Override
	public AVector<AString> appendChunk(AVector<AString> chunk) {
		return toVector().appendChunk(chunk);
	}

	@Override
	public VectorLeaf<AString> getChunk(long offset) {
		return toVector().getChunk(offset);
	}

	@Override
	public boolean isFullyPacked() {
		return toVector().isFullyPacked();
	}

	@Override
	public AVector<AString> concat(ASequence<? extends AString> b) {
		if (b instanceof Path rest) return concat(rest);
		return toVector().concat(b);
	}

	@Override
	public <R extends ACell> AVector<R> map(Function<AString,R> mapper) {
		return toVector().map(mapper);
	}

	@Override
	public <R> R reduce(BiFunction<? super R,? super AString,? extends R> func,R value) {
		return toVector().reduce(func,value);
	}

	@Override
	public boolean anyMatch(Predicate<? super AString> pred) {
		for (int i=0; i<count; i++) {
			if (pred.test(segments[start+i])) return true;
		}
		return false;
	}

	@Override
	public boolean allMatch(Predicate<? super AString> pred) {
		for (int i=0; i<count; i++) {
			if (!pred.test(segments[start+i])) return false;
		}
		return true;
	}

	@Override
	public ListIterator<AString> listIterator() {
		return new PathIterator(0);
	}

	@Override
	public ListIterator<AString> listIterator(long index) {
		if ((index<0)||(index>count)) throw new IndexOutOfBoundsException(index);
		return new PathIterator(index);
	}

	/**
	 * Generic in its element type so that set and add, which take one, refuse any
	 * value as immutable rather than casting it to a string first.
	 */
	private final class PathIterator implements ListIterator<AString> {
		private long pos;

		PathIterator(long pos) {
			this.pos=pos;
		}

		@Override public boolean hasNext() { return pos<count; }

		@Override public AString next() {
			if (pos>=count) throw new NoSuchElementException();
			return (AString)get(pos++);
		}

		@Override public boolean hasPrevious() { return pos>0; }

		@Override public AString previous() {
			if (pos>0) return (AString)get(--pos);
			throw new NoSuchElementException();
		}

		@Override public int nextIndex() { return Utils.checkedInt(pos); }
		@Override public int previousIndex() { return Utils.checkedInt(pos)-1; }

		@Override public void remove() { throw new UnsupportedOperationException(ErrorMessages.immutable(this)); }
		@Override public void set(AString e) { throw new UnsupportedOperationException(ErrorMessages.immutable(this)); }
		@Override public void add(AString e) { throw new UnsupportedOperationException(ErrorMessages.immutable(this)); }
	}
}
