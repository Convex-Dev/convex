package convex.lattice.fs.impl;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.DirectoryStream.Filter;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.util.Set;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Cells;
import convex.core.data.Hash;
import convex.core.data.Index;
import convex.core.data.prim.CVMLong;
import convex.lattice.cursor.ALatticeCursor;
import convex.lattice.cursor.Cursors;
import convex.lattice.fs.DLFS;
import convex.lattice.fs.DLFSCorruptionException;
import convex.lattice.fs.DLFSLattice;
import convex.lattice.fs.DLFSNode;
import convex.lattice.fs.DLFSProvider;
import convex.lattice.fs.DLFileSystem;
import convex.lattice.fs.DLPath;

/**
 * Local DLFS Drive implementation, wrapping a lattice Cursor
 */
public class DLFSLocal extends DLFileSystem {

	// Cursor for filesystem root node. This may be a path into a bigger lattice
	ALatticeCursor<AVector<ACell>> rootCursor;

	public DLFSLocal(DLFSProvider dlfsProvider, String uriPath, AVector<ACell> rootNode) {
		super(dlfsProvider,uriPath,initialTimestamp(rootNode));
		this.rootCursor=Cursors.createLattice(DLFSLattice.INSTANCE, rootNode);
	}

	/**
	 * Creates a DLFSLocal backed by a lattice cursor (which may be a path into a larger lattice).
	 *
	 * @param dlfsProvider Provider for this filesystem
	 * @param uriPath URI path (may be null)
	 * @param cursor Lattice cursor pointing to the DLFS tree
	 */
	public DLFSLocal(DLFSProvider dlfsProvider, String uriPath, ALatticeCursor<AVector<ACell>> cursor) {
		super(dlfsProvider, uriPath, initialTimestamp(cursor.get()));
		this.rootCursor =  cursor;
	}

	/**
	 * Creates a cursor-backed view using a root timestamp captured by the caller.
	 * This avoids re-reading a registry entry while opening an existing drive.
	 */
	public DLFSLocal(DLFSProvider dlfsProvider, String uriPath,
			ALatticeCursor<AVector<ACell>> cursor, CVMLong timestamp) {
		super(dlfsProvider, uriPath, timestamp);
		this.rootCursor=cursor;
	}

	public static DLFSLocal create(DLFSProvider provider) {
		return new DLFSLocal(provider,null,DLFSNode.createDirectory(CVMLong.ZERO));
	}

	@Override
	public AVector<ACell> getNode(DLPath path) throws IOException {
		AVector<ACell> rootNode=rootCursor.get();
		return DLFSNode.resolveNode(rootNode,path);
	}

	@Override
	public CVMLong getTimestamp() {
		CVMLong ctxTs = rootCursor.getContext().getTimestamp();
		return (ctxTs != null) ? ctxTs : super.getTimestamp();
	}

	@Override
	protected DLDirectoryStream newDirectoryStream(DLPath dir, Filter<? super Path> filter) throws IOException {
		AVector<ACell> rootNode=rootCursor.get();
		Index<?,?> entries=DLFSNode.resolveDirectoryEntries(rootNode,dir);
		return DLDirectoryStream.create(dir,entries,filter);
	}

	@Override
	public SeekableByteChannel newByteChannel(DLPath path, Set<? extends OpenOption> options, FileAttribute<?>[] attrs) throws IOException {
		path=path.normalize();
		return DLFileChannel.create(this,options,path);
	}

	@Override
	protected synchronized DLPath createDirectory(DLPath dir, FileAttribute<?>[] attrs) throws IOException {
		AString name=dir.getCVMFileName();
		if (name==null) throw new FileAlreadyExistsException(DLFS.ROOT_STRING);
		DLPath parent=dir.getParent();
		if (parent==null) throw new FileAlreadyExistsException(dir.toString());
		AVector<ACell> rootNode=rootCursor.get();
		AVector<ACell> parentNode=DLFSNode.resolveValidPath(rootNode, parent);
		if (parentNode==null) {
			throw new NoSuchFileException(parent.toString());
		}
		requireAbsent(parentNode,name,dir);
		updateNode(dir,DLFSNode.createDirectory(getTimestamp()));
		return dir;
	}
	
	@Override
	public synchronized AVector<ACell> createFile(DLPath path) throws IOException {
		AString name=path.getCVMFileName();
		path=path.toAbsolutePath();
		DLPath parent=path.getParent();
		if (parent==null) throw new FileAlreadyExistsException(path.toString()); // trying to create root
		AVector<ACell> rootNode=rootCursor.get();
		AVector<ACell> parentNode=DLFSNode.resolveValidPath(rootNode, parent);
		if (parentNode==null) {
			throw new NoSuchFileException(parent.toString(), null, "Parent directory does not exist");
		}
		requireAbsent(parentNode,name,path);
		AVector<ACell> newNode=DLFSNode.createEmptyFile(getTimestamp());
		updateNode(path,newNode);
		return newNode;
	}
	

	@Override
	public synchronized void delete(DLPath path) throws IOException {
		final DLPath p=path.toAbsolutePath();
		if (p.getNameCount()==0) {
			throw new IOException("Can't delete DLFS Root node");
		}

		try {
			rootCursor.updateAndGet(rootNode->{
				AVector<ACell> parent=resolveValidUnchecked(rootNode,p.getParent());
				if (parent==null) throw new UncheckedIOException(new NoSuchFileException(p.toString()));
				ACell raw=DLFSNode.getRawEntry(DLFSNode.getDirectoryEntries(parent),p.getCVMFileName());
				if (raw==null) throw new UncheckedIOException(new NoSuchFileException(p.toString()));
				if (raw instanceof AVector<?> vector) {
					ACell contents=(vector.count()>DLFSNode.POS_DIR)?vector.get(DLFSNode.POS_DIR):null;
					if (contents instanceof Index<?,?> entries && !entries.isEmpty()) {
						throw new UncheckedIOException(new DirectoryNotEmptyException(p.toString()));
					}
				}
				return DLFSNode.deleteNode(rootNode,p,getTimestamp());
			});
		} catch (UncheckedIOException e) {
			throw e.getCause();
		}
	}

	@Override
	public synchronized void copy(DLPath source, DLPath target, boolean recursive) throws IOException {
		DLPath src=source.toAbsolutePath().normalize();
		DLPath dst=target.toAbsolutePath().normalize();
		if (src.getNameCount()==0) throw new FileSystemException(src.toString(),dst.toString(),"Cannot copy the DLFS root");
		if (dst.getNameCount()==0) throw new FileAlreadyExistsException(dst.toString());

		CVMLong utime=getTimestamp();
		try {
			rootCursor.updateAndGet(root->{
				AVector<ACell> sourceNode=resolveNodeUnchecked(root,src);
				if (sourceNode==null) throw new UncheckedIOException(new NoSuchFileException(src.toString()));
				if (src.equals(dst)) return root;
				AVector<ACell> targetParent=requireDirectory(root,dst.getParent());
				requireAbsentUnchecked(targetParent,dst.getCVMFileName(),dst);

				AVector<ACell> copiedNode=sourceNode.assoc(DLFSNode.POS_UTIME,utime);
				if (DLFSNode.isDirectory(sourceNode)&&!recursive) {
					copiedNode=copiedNode.assoc(DLFSNode.POS_DIR,Index.none());
					if (copiedNode.count()>DLFSNode.NODE_LENGTH) copiedNode=copiedNode.slice(0,DLFSNode.NODE_LENGTH);
				}
				return DLFSNode.updateNode(root,dst,copiedNode,utime);
			});
		} catch (UncheckedIOException e) {
			throw e.getCause();
		}
	}

	@Override
	public synchronized void move(DLPath source, DLPath target) throws IOException {
		DLPath src=source.toAbsolutePath().normalize();
		DLPath dst=target.toAbsolutePath().normalize();
		if (src.getNameCount()==0) throw new FileSystemException(src.toString(),dst.toString(),"Cannot move the DLFS root");
		if (dst.getNameCount()==0) throw new FileAlreadyExistsException(dst.toString());

		CVMLong utime=getTimestamp();
		try {
			rootCursor.updateAndGet(root->{
				AVector<ACell> sourceNode=resolveValidUnchecked(root,src);
				if (sourceNode==null) throw new UncheckedIOException(new NoSuchFileException(src.toString()));
				if (src.equals(dst)) return root;
				if (DLFSNode.isDirectory(sourceNode)&&dst.startsWith(src)) {
					throw new UncheckedIOException(new FileSystemException(src.toString(),dst.toString(),"Cannot move a directory into itself"));
				}
				AVector<ACell> targetParent=requireDirectory(root,dst.getParent());
				requireAbsentUnchecked(targetParent,dst.getCVMFileName(),dst);

				AVector<ACell> updated=DLFSNode.deleteNode(root,src,utime);
				return DLFSNode.updateNode(updated,dst,sourceNode,utime);
			});
		} catch (UncheckedIOException e) {
			throw e.getCause();
		}
	}

	private static AVector<ACell> requireDirectory(AVector<ACell> rootNode, DLPath path) {
		AVector<ACell> node=resolveValidUnchecked(rootNode,path);
		if (node==null) throw new UncheckedIOException(new NoSuchFileException(path.toString()));
		if (!DLFSNode.isDirectory(node)) throw new UncheckedIOException(new NotDirectoryException(path.toString()));
		return node;
	}

	@Override
	public synchronized AVector<ACell> updateNode(DLPath dir, AVector<ACell> newNode) throws IOException {
		if (newNode!=null && !DLFSNode.isValidNodeShallow(newNode)) throw new IllegalArgumentException("Invalid replacement DLFS node");
		try {
			rootCursor.updateAndGet(rootNode->{
				DLPath parent=dir.toAbsolutePath().normalize().getParent();
				if (parent!=null) {
					AVector<ACell> parentNode=resolveValidUnchecked(rootNode,parent);
					if (parentNode==null) throw new UncheckedIOException(new NoSuchFileException(parent.toString()));
					if (!DLFSNode.isDirectory(parentNode)) throw new UncheckedIOException(new NotDirectoryException(parent.toString()));
				}
				return DLFSNode.updateNode(rootNode,dir,newNode,getTimestamp());
			});
		} catch (UncheckedIOException e) {
			throw e.getCause();
		}
		return newNode;
	}

	@Override
	protected void checkAccess(DLPath path) throws IOException {
		AVector<ACell> rootNode=rootCursor.get();
		AVector<ACell> node=DLFSNode.resolveNode(rootNode,path);
		if (node==null) {
			throw new NoSuchFileException(path.toString());
		}
	}

	@Override
	public Hash getRootHash() {
		return Cells.getHash(rootCursor.get());
	}

	@Override
	public void merge(AVector<ACell> other) {
		rootCursor.merge(other);
	}

	@Override
	public DLFSLocal fork() {
		return new DLFSLocal(provider(), uriPath, rootCursor.fork());
	}

	@Override
	public void sync() {
		rootCursor.sync();
	}

	@Override
	public DLFSLocal clone() {
		return new DLFSLocal(provider(), uriPath, rootCursor.get());
	}

	private static CVMLong initialTimestamp(AVector<ACell> rootNode) {
		if (rootNode!=null && rootNode.count()>DLFSNode.POS_UTIME) {
			ACell value=rootNode.get(DLFSNode.POS_UTIME);
			if (value instanceof CVMLong timestamp) return timestamp;
		}
		return CVMLong.ZERO;
	}

	private static void requireAbsent(AVector<ACell> parent, AString name, DLPath path) throws IOException {
		ACell existing=DLFSNode.getRawEntry(DLFSNode.getDirectoryEntries(parent),name);
		if (existing==null) return;
		if (!(existing instanceof AVector<?> vector)) throw new DLFSCorruptionException(path.toString(),"Stored value is not a DLFS node");
		@SuppressWarnings("unchecked")
		AVector<ACell> node=(AVector<ACell>)vector;
		if (!DLFSNode.isValidNodeShallow(node)) throw new DLFSCorruptionException(path.toString(),"Malformed existing DLFS node");
		throw new FileAlreadyExistsException(path.toString());
	}

	private static void requireAbsentUnchecked(AVector<ACell> parent, AString name, DLPath path) {
		try {
			requireAbsent(parent,name,path);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static AVector<ACell> resolveNodeUnchecked(AVector<ACell> root, DLPath path) {
		try {
			return DLFSNode.resolveNode(root,path);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static AVector<ACell> resolveValidUnchecked(AVector<ACell> root, DLPath path) {
		try {
			return DLFSNode.resolveValidPath(root,path);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
