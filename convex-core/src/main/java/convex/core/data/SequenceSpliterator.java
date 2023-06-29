package convex.core.data;

import java.util.Spliterator;
import java.util.function.Consumer;

public class SequenceSpliterator<T extends ACell> implements Spliterator<T> {
	long start;
	long end;
	private ASequence<T> seq;
	
	public SequenceSpliterator(ASequence<T> seq, long start, long end) {
		this.start=start;
		this.end=end;
		this.seq=seq;
	}

	@Override
	public boolean tryAdvance(Consumer<? super T> action) {
		if (start<end) {
			T value=seq.get(start++);
			action.accept(value);
			return true;
		} else {
			return false;
		}
	}

	@Override
	public SequenceSpliterator<T> trySplit() {
		if (start+1>=end) {
			// Nothing worth splitting
			return null;
		}
		long split=(start+end)/2;

		SequenceSpliterator<T> result= new SequenceSpliterator<T>(seq,split,end);
		this.end=split;
		return result;
	}

	@Override
	public long estimateSize() {
		return end-start;
	}

	@Override
	public int characteristics() {
		return Spliterator.IMMUTABLE | Spliterator.SIZED | Spliterator.SUBSIZED | Spliterator.ORDERED | Spliterator.CONCURRENT;
	}

}
