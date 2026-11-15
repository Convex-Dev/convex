package convex.etch;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;

import org.junit.jupiter.api.Test;

/** Growth of an FFM mapping cut off by an Error leaves no mapping behind. */
public class FFMFileMapperFailureTest {

	@Test
	public void testMappingCutOffByErrorIsReleased() throws Exception {
		File file=File.createTempFile("etch-ffm-error",".dat");
		try (RandomAccessFile data=new RandomAccessFile(file,"rw")) {
			FailingMapChannel channel=new FailingMapChannel(data.getChannel());
			FFMFileMapper mapper=new FFMFileMapper(channel);
			try {
				// The new region is mapped, then the Error strikes before the
				// mapper has published it
				byte[] record=new byte[] { 1,2,3,4,5,6,7,8 };
				assertThrows(OutOfMemoryError.class,()->mapper.append(record,0,record.length,1,null));
				Arena failed=channel.failedArena;
				assertNotNull(failed);
				assertFalse(failed.scope().isAlive(),"Mapping left open after an Error");

				assertEquals(0L,mapper.append(record,0,record.length,1,null));
				byte[] actual=new byte[record.length];
				mapper.read(0L,actual,0,actual.length,null);
				assertArrayEquals(record,actual);
			} finally {
				mapper.close();
			}
		} finally {
			if (!file.delete()) file.deleteOnExit();
		}
	}

	/** File channel whose first segment mapping succeeds and then throws an Error. */
	private static final class FailingMapChannel extends FileChannel {
		private final FileChannel delegate;
		private boolean failNext=true;
		private volatile Arena failedArena;

		private FailingMapChannel(FileChannel delegate) {
			this.delegate=delegate;
		}

		@Override
		public MemorySegment map(MapMode mode, long offset, long size, Arena arena) throws IOException {
			MemorySegment segment=delegate.map(mode,offset,size,arena);
			synchronized (this) {
				if (failNext) {
					failNext=false;
					failedArena=arena;
					throw new OutOfMemoryError("Simulated");
				}
			}
			return segment;
		}

		@Override
		public int read(ByteBuffer dst) throws IOException {
			return delegate.read(dst);
		}

		@Override
		public long read(ByteBuffer[] dsts, int offset, int length) throws IOException {
			return delegate.read(dsts,offset,length);
		}

		@Override
		public int write(ByteBuffer src) throws IOException {
			return delegate.write(src);
		}

		@Override
		public long write(ByteBuffer[] srcs, int offset, int length) throws IOException {
			return delegate.write(srcs,offset,length);
		}

		@Override
		public long position() throws IOException {
			return delegate.position();
		}

		@Override
		public FileChannel position(long newPosition) throws IOException {
			delegate.position(newPosition);
			return this;
		}

		@Override
		public long size() throws IOException {
			return delegate.size();
		}

		@Override
		public FileChannel truncate(long size) throws IOException {
			delegate.truncate(size);
			return this;
		}

		@Override
		public void force(boolean metaData) throws IOException {
			delegate.force(metaData);
		}

		@Override
		public long transferTo(long position, long count, WritableByteChannel target) throws IOException {
			return delegate.transferTo(position,count,target);
		}

		@Override
		public long transferFrom(ReadableByteChannel src, long position, long count) throws IOException {
			return delegate.transferFrom(src,position,count);
		}

		@Override
		public int read(ByteBuffer dst, long position) throws IOException {
			return delegate.read(dst,position);
		}

		@Override
		public int write(ByteBuffer src, long position) throws IOException {
			return delegate.write(src,position);
		}

		@Override
		public MappedByteBuffer map(MapMode mode, long position, long size) throws IOException {
			return delegate.map(mode,position,size);
		}

		@Override
		public FileLock lock(long position, long size, boolean shared) throws IOException {
			return delegate.lock(position,size,shared);
		}

		@Override
		public FileLock tryLock(long position, long size, boolean shared) throws IOException {
			return delegate.tryLock(position,size,shared);
		}

		@Override
		protected void implCloseChannel() throws IOException {
			delegate.close();
		}
	}
}
