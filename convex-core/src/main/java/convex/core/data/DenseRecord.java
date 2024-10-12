package convex.core.data;

import java.util.ListIterator;
import java.util.function.Consumer;
import java.util.function.Function;

import convex.core.data.type.AType;

public class DenseRecord extends ACAD3Record {

	protected AVector<ACell> data;
	
	public DenseRecord(long count) {
		super(count);
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

	@Override
	public <R extends ACell> ASequence<R> map(Function<? super ACell, ? extends R> mapper) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void forEach(Consumer<? super ACell> action) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void visitElementRefs(Consumer<Ref<ACell>> f) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public ASequence<ACell> concat(ASequence<? extends ACell> vals) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ASequence<ACell> next() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ASequence<ACell> empty() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ACell get(long index) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Ref<ACell> getElementRef(long index) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ASequence<ACell> assoc(long i, ACell value) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ASequence<ACell> conj(ACell value) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ASequence<ACell> slice(long start, long end) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public AList<ACell> cons(ACell x) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public AVector<ACell> subVector(long start, long length) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	protected ListIterator<ACell> listIterator(long l) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ASequence<ACell> reverse() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public AType getType() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public AVector<ACell> toVector() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	protected <R> void copyToArray(R[] arr, int offset) {
		// TODO Auto-generated method stub
		
	}

}
