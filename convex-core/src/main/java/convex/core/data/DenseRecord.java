package convex.core.data;

import java.util.ListIterator;
import java.util.function.Consumer;
import java.util.function.Function;

import convex.core.data.type.AType;
import convex.core.data.type.Types;
import convex.core.exceptions.BadFormatException;

public class DenseRecord extends ACAD3Record {

	protected final AVector<ACell> data;
	
	protected DenseRecord(byte tag, AVector<ACell> data) {
		super(tag,data.count());
		this.data=data;
	}
	
	@SuppressWarnings("unchecked")
	public static DenseRecord create(int tag,AVector<?> data) {
		if (data==null) return null;
		if (Tag.category(tag)!=Tag.DENSE_RECORD_BASE) return null; // not an extension value
		
		return new DenseRecord((byte)tag,(AVector<ACell>) data);
	}
	
	@Override
	public int estimatedEncodingSize() {
		return data.estimatedEncodingSize();
	}
	
	@Override
	public int encodeRaw(byte[] bs, int pos) {
		return data.encodeRaw(bs, pos);
	}
	
	public static DenseRecord read(byte tag, Blob b, int pos) throws BadFormatException {
		AVector<ACell> data=Vectors.read(b, pos);
		
		Blob enc=data.cachedEncoding();
		data.attachEncoding(null); // clear invalid encoding
		
		DenseRecord dr=create(tag,data);
		if ((enc!=null)&&(enc.byteAt(0)==tag)) {
			dr.attachEncoding(enc);
		}
		return dr;
	}

	@Override
	public ListIterator<ACell> listIterator() {
		return data.listIterator();
	}

	@Override
	public ListIterator<ACell> listIterator(int index) {
		return data.listIterator(index);
	}

	@Override
	public long longIndexOf(ACell value) {
		return data.longIndexOf(value);
	}

	@Override
	public long longLastIndexOf(ACell value) {
		return data.longLastIndexOf(value);
	}

	@SuppressWarnings("unchecked")
	@Override
	public <R extends ACell> ASequence<R> map(Function<? super ACell, ? extends R> mapper) {
		AVector<ACell> rdata=data.map(mapper);
		return (ASequence<R>) rdata;
	}

	@Override
	public void forEach(Consumer<? super ACell> action) {
		data.forEach(action);
	}

	@Override
	public void visitElementRefs(Consumer<Ref<ACell>> f) {
		data.visitElementRefs(f);
	}

	@Override
	public ASequence<ACell> concat(ASequence<? extends ACell> vals) {
		return data.concat(vals);
	}

	@Override
	public ASequence<ACell> next() {
		return data.next();
	}

	@Override
	public ASequence<ACell> empty() {
		return Vectors.empty();
	}

	@Override
	public ACell get(long index) {
		return data.get(index);
	}

	@Override
	public Ref<ACell> getElementRef(long index) {
		return data.getElementRef(index);
	}

	@Override
	public ASequence<ACell> assoc(long i, ACell value) {
		AVector<ACell> newData=data.assoc(i, value);
		return newData;
	}

	@Override
	public ASequence<ACell> conj(ACell value) {
		return data.conj(value);
	}

	@Override
	public ASequence<ACell> slice(long start, long end) {
		return data.slice(start,end);
	}

	@Override
	public AList<ACell> cons(ACell x) {
		return data.cons(x);
	}

	@Override
	public AVector<ACell> subVector(long start, long length) {
		return data.subVector(start, length);
	}

	@Override
	protected ListIterator<ACell> listIterator(long l) {
		return data.listIterator(l);
	}

	@Override
	public ASequence<ACell> reverse() {
		return data.reverse();
	}

	@Override
	public AType getType() {
		return Types.CAD3;
	}

	@Override
	public AVector<ACell> toVector() {
		return data;
	}

	@Override
	protected <R> void copyToArray(R[] arr, int offset) {
		data.copyToArray(arr, offset);
	}

	@Override
	public int getRefCount() {
		return data.getRefCount();
	}

	@Override
	public Ref<ACell> getRef(int i) {
		return data.getRef(i);
	}

	@Override
	public ACell updateRefs(IRefFunction func) {
		AVector<ACell> newData=data.updateRefs(func);
		if (newData==data) return this;
		DenseRecord dr= new DenseRecord(tag,newData);
		dr.attachEncoding(getEncoding());
		return dr;
	}


}
