package convex.dlfs.impl;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.NoSuchElementException;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.dlfs.DLFSNode;
import convex.dlfs.DLPath;

public class DLDirectoryStream implements DirectoryStream<Path> {

	public class DIterator implements Iterator<Path> {
		long pos=0;
		
		@Override
		public boolean hasNext() {
			return pos<dirs.count();
		}

		@Override
		public DLPath next() {
			if (pos>=dirs.count()) throw new NoSuchElementException();
			return base.resolve(dirs.entryAt(pos++).getKey());
		}

	}

	private AHashMap<AString, AVector<ACell>> dirs;
	private DLPath base;

	public DLDirectoryStream(DLPath base, AHashMap<AString, AVector<ACell>> dirs) {
		this.base=base;
		this.dirs=dirs;
	}

	@Override
	public void close() throws IOException {
		// Ignore

	}

	@Override
	public DIterator iterator() {
		return new DIterator();
	}

	public static DLDirectoryStream create(DLPath base, AVector<ACell> dirNode) {
		AHashMap<AString, AVector<ACell>> dirs = DLFSNode.getDirectoryEntries(dirNode);
		if (dirs==null) return null;
		return new DLDirectoryStream(base,dirs);
	}

}
