package convex.benchmarks;

import java.util.Random;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.*;

import convex.core.data.ABlob;
import convex.core.data.Blob;
import convex.core.data.util.BlobBuilder;
import convex.core.util.Utils;

/** Immutable blob appends versus the original copying builder. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(2)
@Warmup(iterations=3,time=1)
@Measurement(iterations=5,time=1)
public class BlobBuilderBenchmark {
	@Param({"64","4096","65536","1048576"}) public int size;
	@Param({"0","1","4096"}) public int prefix;
	@Param({"tree","flat"}) public String representation;
	private ABlob input;
	private byte[] initial;

	@Setup public void setup() {
		Blob flat=Blob.createRandom(new Random(12345),size);
		input=representation.equals("tree")?flat.getCanonical():flat;
		initial=new byte[prefix];
		if (!sharing().equals(copying())) throw new AssertionError("Append mismatch");
	}

	@Benchmark public ABlob sharing() {
		BlobBuilder builder=new BlobBuilder();
		builder.append(initial);
		return builder.append(input).toBlob();
	}

	@Benchmark public ABlob copying() {
		BlobBuilder builder=new CopyingBuilder();
		builder.append(initial);
		return builder.append(input).toBlob();
	}

	/** Original append implementation and its private helpers, retained as a baseline. */
	private static class CopyingBuilder extends BlobBuilder {
		@Override public BlobBuilder append(ABlob b) {
			long blen=b.count();
			if (blen==0) return this;
			long spare=spare();
			if (blen<=spare) {
				ensureArray(arrayPos()+blen);
				b.getBytes(tail,arrayPos());
				count+=blen;
				if (blen==spare) completeChunk();
			} else {
				append(b.slice(0,spare));
				for (long off=spare;off<blen;off+=Blob.CHUNK_LENGTH) {
					long take=Math.min(Blob.CHUNK_LENGTH,blen-off);
					append(b.slice(off,off+take));
				}
			}
			return this;
		}

		// Use the same helpers for initial bytes and subsequent immutable input,
		// as the original class did, preserving their JIT branch profiles.
		@Override public void append(byte[] bs, int offset, int length) {
			while (length>0) {
				int split=Math.min(length,spare());
				int pos=arrayPos();
				ensureArray(pos+split);
				System.arraycopy(bs,offset,tail,pos,split);
				count+=split;
				length-=split;
				offset+=split;
				if (spare()==0) completeChunk();
			}
		}

		private int spare() { return (int)(Blob.CHUNK_LENGTH-(count-acc.count())); }
		private int arrayPos() { return Utils.checkedInt(count-acc.count()); }

		private void completeChunk() {
			if (tail.length!=Blob.CHUNK_LENGTH) throw new IllegalStateException("tail not complete! Has length: "+tail.length);
			acc=acc.append(Blob.wrap(tail,0,arrayPos()));
			tail=null;
		}

		private void ensureArray(long n) {
			if (n>Blob.CHUNK_LENGTH) throw new IllegalStateException("Invalid array size request: "+n);
			if (tail==null) tail=new byte[Utils.checkedInt(Math.min(Blob.CHUNK_LENGTH,n*2))];
			if (tail.length>=n) return;
			byte[] newTail=new byte[Math.min(Blob.CHUNK_LENGTH,Math.max((int)n,tail.length*2))];
			System.arraycopy(tail,0,newTail,0,arrayPos());
			tail=newTail;
		}
	}
}
