package convex.lattice.fs.impl;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.NoSuchElementException;

import convex.core.data.AString;
import convex.core.data.Index;
import convex.core.data.MapEntry;
import convex.lattice.fs.DLFSCorruptionException;
import convex.lattice.fs.DLPath;

public class DLDirectoryStream implements DirectoryStream<Path> {

	/**
	 * Iterator over the directory's live entries. Deleted children are recorded as
	 * tombstones in the parent node's separate POS_TOMBS index, not in the live entries,
	 * so this map already contains exactly the visible children.
	 */
	public class DIterator implements Iterator<Path> {
		long pos=0;
		DLPath next;
		boolean prepared;

		private void prepare() {
			if (prepared) return;
			while (pos<dirs.count()) {
				MapEntry<?,?> entry=dirs.entryAt(pos++);
				if (!(entry.getKey() instanceof AString name)) {
					throw new DirectoryIteratorException(new DLFSCorruptionException(base.toString(),
						"Directory contains a non-string name"));
				}
				DLPath candidate=base.resolve(name);
				try {
					if (filter==null || filter.accept(candidate)) {
						next=candidate;
						prepared=true;
						return;
					}
				} catch (IOException e) {
					throw new DirectoryIteratorException(e);
				}
			}
			prepared=true;
			next=null;
		}

		@Override
		public boolean hasNext() {
			prepare();
			return next!=null;
		}

		@Override
		public DLPath next() {
			prepare();
			if (next==null) throw new NoSuchElementException();
			DLPath result=next;
			prepared=false;
			next=null;
			return result;
		}

	}

	private Index<?,?> dirs;
	private DLPath base;
	private Filter<? super Path> filter;
	private boolean open=true;
	private boolean iterated=false;

	public DLDirectoryStream(DLPath base, Index<?,?> dirs, Filter<? super Path> filter) {
		this.base=base;
		this.dirs=dirs;
		this.filter=filter;
	}

	@Override
	public void close() throws IOException {
		open=false;
	}

	@Override
	public synchronized DIterator iterator() {
		if (!open) throw new IllegalStateException("Directory stream is closed");
		if (iterated) throw new IllegalStateException("Directory stream iterator already obtained");
		iterated=true;
		return new DIterator();
	}

	public static DLDirectoryStream create(DLPath base, Index<?,?> dirs, Filter<? super Path> filter) {
		if (dirs==null) return null;
		return new DLDirectoryStream(base,dirs,filter);
	}

}
