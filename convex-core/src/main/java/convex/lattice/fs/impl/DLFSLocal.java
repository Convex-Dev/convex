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
import java.nio.file.attribute.FileTime;
import java.util.Set;
import java.util.function.UnaryOperator;

import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Cells;
import convex.core.data.Hash;
import convex.core.data.Index;
import convex.core.data.prim.CVMLong;
import convex.core.store.AStore;
import convex.core.store.MemoryStore;
import convex.core.util.Utils;
import convex.lattice.LatticeComponent;
import convex.lattice.LatticeContext;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.Cursors;
import convex.lattice.fs.DLFS;
import convex.lattice.fs.DLFSCorruptionException;
import convex.lattice.fs.DLFSLattice;
import convex.lattice.fs.DLFSLocation;
import convex.lattice.fs.DLFSNode;
import convex.lattice.fs.DLFSProvider;
import convex.lattice.fs.DLFileSystem;
import convex.lattice.fs.DLPath;

/**
 * Local DLFS Drive implementation, wrapping a lattice Cursor
 */
public class DLFSLocal extends DLFileSystem {

	// Cursor for filesystem root node. This may be a path into a bigger lattice
	AForkableCursor<AVector<ACell>> rootCursor;
	// Store for incremental blob checkpoints during streamed writes, or null for heap-only
	private final AStore store;
	private final DLFSLocation location;
	private volatile LatticeContext context=LatticeContext.EMPTY;
	public LatticeContext getContext() { return context; }
	public void setContext(LatticeContext context) {
		this.context=java.util.Objects.requireNonNull(context);
	}

	public DLFSLocal(DLFSProvider dlfsProvider, String uriPath, AVector<ACell> rootNode) {
		this(dlfsProvider,uriPath,Cursors.root(rootNode),null);
	}

	/**
	 * Creates a DLFSLocal backed by a lattice cursor (which may be a path into a larger lattice).
	 * File data remains heap-backed; use {@link #create(DLFSProvider, String, AForkableCursor, AStore)}
	 * for incremental blob persistence.
	 *
	 * @param dlfsProvider Provider for this filesystem
	 * @param uriPath URI path (may be null)
	 * @param cursor Lattice cursor pointing to the DLFS tree
	 */
	public DLFSLocal(DLFSProvider dlfsProvider, String uriPath, AForkableCursor<AVector<ACell>> cursor) {
		this(dlfsProvider,uriPath,cursor,null);
	}

	/**
	 * Creates a cursor-backed view which checkpoints blob branches into the supplied
	 * store when non-null. Intended for adapters which may also operate detached.
	 */
	protected DLFSLocal(DLFSProvider dlfsProvider, String uriPath,
			AForkableCursor<AVector<ACell>> cursor, AStore store) {
		this(dlfsProvider,uriPath,cursor,store,DLFSLocation.at(cursor));
	}

	/** Binds a NIO view to the working location known by its domain component. */
	protected DLFSLocal(DLFSProvider dlfsProvider,String uriPath,
			AForkableCursor<AVector<ACell>> cursor,AStore store,DLFSLocation location) {
		super(dlfsProvider,uriPath);
		this.rootCursor=cursor;
		this.store=store;
		this.location=java.util.Objects.requireNonNull(location);
	}

	@Override public boolean isSameDrive(DLFileSystem other) {
		return other instanceof DLFSLocal local && location.equals(local.location);
	}

	/** Creates a view with an explicit component location and optional blob store. */
	public static DLFSLocal create(DLFSProvider provider,String uriPath,
			AForkableCursor<AVector<ACell>> cursor,AStore store,DLFSLocation location) {
		return new DLFSLocal(provider,uriPath,cursor,store,location);
	}

	/**
	 * Creates a cursor-backed view with incremental blob persistence in the
	 * supplied store. The caller retains ownership of the store.
	 *
	 * @param dlfsProvider DLFS provider
	 * @param uriPath URI path (may be null)
	 * @param cursor Cursor pointing to the DLFS tree
	 * @param store Store used for streamed blob persistence
	 * @return Store-backed filesystem view
	 */
	public static DLFSLocal create(DLFSProvider dlfsProvider, String uriPath,
			AForkableCursor<AVector<ACell>> cursor, AStore store) {
		if (store==null) throw new IllegalArgumentException("DLFS persistence store must not be null");
		return new DLFSLocal(dlfsProvider,uriPath,cursor,store);
	}

	public static DLFSLocal create(DLFSProvider provider) {
		return new DLFSLocal(provider,null,DLFSNode.createDirectory(CVMLong.ZERO));
	}

	@Override
	public AVector<ACell> getNode(DLPath path) throws IOException {
		AVector<ACell> rootNode=rootCursor.get();
		return DLFSNode.resolveNode(rootNode,path);
	}

	@Override public void setTimes(DLPath path,FileTime modified,FileTime accessed,FileTime created) throws IOException {
		// Access and creation times are unsupported. Ignore them without a lattice write.
		if (modified==null) return;
		CVMLong operationTime=currentTimestamp();
		try {
			rootCursor.updateAndGet(root->{
				AVector<ACell> node=resolveValidUnchecked(root,path);
				if (node==null) throw new UncheckedIOException(new NoSuchFileException(path.toString()));
				AVector<ACell> updated=DLFSNode.withMTime(node,CVMLong.create(modified.toMillis())).assoc(DLFSNode.POS_UTIME,operationTime);
				return DLFSNode.updateNode(root,path,updated,operationTime);
			});
		} catch (UncheckedIOException e) { throw e.getCause(); }
	}

	/**
	 * Returns the underlying cursor. Applications control mutation time by
	 * configuring this filesystem's explicit {@link convex.lattice.LatticeContext}.
	 *
	 * @return cursor holding this drive's root node
	 */
	public AForkableCursor<AVector<ACell>> getCursor() {
		return rootCursor;
	}

	// Time comes from the configured policy. DLFS never advances it implicitly.
	protected CVMLong currentTimestamp() {
		return getContext().currentTimestamp();
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

	/**
	 * Checkpoints immutable blob branches into this filesystem's store, if any. Part
	 * of the channel write protocol; subclasses may override it to apply their own
	 * persistence policy.
	 */
	protected ABlob checkpointBlob(ABlob data) throws IOException {
		return (store==null)?data:Cells.persist(data,store);
	}

	/**
	 * Atomically transforms the latest file data across all views of this drive.
	 * Checkpointing runs before installation, outside cursor retry callbacks. A
	 * changed root with unchanged file data reuses the prepared blob; only a change
	 * to this file's data requires recomputation and another checkpoint.
	 */
	AVector<ACell> updateFileData(DLPath path, UnaryOperator<ABlob> transform,
			CVMLong operationTime, boolean checkpoint) throws IOException {
		ABlob preparedFrom=null;
		ABlob prepared=null;
		while (true) {
			AVector<ACell> root=rootCursor.get();
			AVector<ACell> node=DLFSNode.resolveValidPath(root,path);
			ABlob data=(node==null)?null:DLFSNode.getData(node);
			if (data==null) throw new NoSuchFileException(path.toString());
			if (data!=preparedFrom) {
				prepared=transform.apply(data);
				if (prepared==data) return node;
				if (checkpoint) prepared=checkpointBlob(prepared);
				preparedFrom=data;
			}
			AVector<ACell> updated=DLFSNode.withContentTime(node.assoc(DLFSNode.POS_DATA,prepared),operationTime);
			AVector<ACell> next=DLFSNode.updateNode(root,path,updated,operationTime);
			if (rootCursor.compareAndSet(root,next)) return updated;
		}
	}

	@Override
	protected synchronized DLPath createDirectory(DLPath dir, FileAttribute<?>[] attrs) throws IOException {
		CVMLong operationTime=currentTimestamp();
		createNode(dir,DLFSNode.createDirectory(operationTime),operationTime);
		return dir;
	}
	
	@Override
	public synchronized AVector<ACell> createFile(DLPath path) throws IOException {
		CVMLong operationTime=currentTimestamp();
		AVector<ACell> newNode=DLFSNode.createEmptyFile(operationTime);
		createNode(path,newNode,operationTime);
		return newNode;
	}

	private void createNode(DLPath path, AVector<ACell> node, CVMLong operationTime) throws IOException {
		DLPath target=path.toAbsolutePath();
		DLPath parent=target.getParent();
		if (parent==null) throw new FileAlreadyExistsException(path.toString());
		try {
			rootCursor.updateAndGet(root -> {
				AVector<ACell> directory=requireDirectory(root,parent);
				requireAbsentUnchecked(directory,target.getCVMFileName(),target);
				return DLFSNode.updateNode(root,target,node,operationTime);
			});
		} catch (UncheckedIOException e) {
			throw e.getCause();
		}
	}
	

	@Override
	public synchronized void delete(DLPath path) throws IOException {
		final DLPath p=path.toAbsolutePath();
		if (p.getNameCount()==0) {
			throw new IOException("Can't delete DLFS Root node");
		}

		CVMLong operationTime=currentTimestamp();
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
				return DLFSNode.deleteNode(rootNode,p,operationTime);
			});
		} catch (UncheckedIOException e) {
			throw e.getCause();
		}
	}

	@Override
	public void copy(DLPath source, DLPath target, boolean recursive) throws IOException {
		copy(source,target,recursive,false);
	}

	@Override
	public void copy(DLPath source, DLPath target, boolean recursive, boolean replaceExisting) throws IOException {
		copy(source,target,recursive,replaceExisting,false);
	}

	@Override
	public void copy(DLPath source,DLPath target,boolean recursive,boolean replaceExisting,boolean copyAttributes) throws IOException {
		if (!isSameDrive(target.getFileSystem())) {
			transfer(source,target,recursive,replaceExisting,false,copyAttributes);
			return;
		}
		DLPath src=source.toAbsolutePath().normalize();
		DLPath dst=getPath(target.toString()).toAbsolutePath().normalize();
		if (src.getNameCount()==0) throw new FileSystemException(src.toString(),dst.toString(),"Cannot copy the DLFS root");
		if (dst.getNameCount()==0) throw new FileAlreadyExistsException(dst.toString());

		CVMLong utime=currentTimestamp();
		try {
			rootCursor.updateAndGet(root->{
				AVector<ACell> sourceNode=resolveNodeUnchecked(root,src);
				if (sourceNode==null) throw new UncheckedIOException(new NoSuchFileException(src.toString()));
				if (src.equals(dst)) return root;
				AVector<ACell> targetParent=requireDirectory(root,dst.getParent());
				AVector<ACell> targetNode=resolveNodeUnchecked(root,dst);
				if (targetNode!=null) {
					if (!replaceExisting) requireAbsentUnchecked(targetParent,dst.getCVMFileName(),dst);
					if (DLFSNode.isDirectory(targetNode)&&!DLFSNode.isEmpty(targetNode)) {
						throw new UncheckedIOException(new DirectoryNotEmptyException(dst.toString()));
					}
				}

				AVector<ACell> copiedNode=copyTimes(sourceNode,utime,copyAttributes);
				if (DLFSNode.isDirectory(sourceNode)&&!recursive) {
					copiedNode=DLFSNode.withoutChildren(copiedNode);
				}
				return DLFSNode.updateEntry(root,dst,copiedNode,utime);
			});
		} catch (UncheckedIOException e) {
			throw e.getCause();
		}
	}

	@Override
	public void move(DLPath source, DLPath target) throws IOException {
		move(source,target,false);
	}

	@Override
	public void move(DLPath source, DLPath target, boolean replaceExisting) throws IOException {
		if (!isSameDrive(target.getFileSystem())) {
			transfer(source,target,true,replaceExisting,true,true);
			return;
		}
		DLPath src=source.toAbsolutePath().normalize();
		DLPath dst=getPath(target.toString()).toAbsolutePath().normalize();
		if (src.getNameCount()==0) throw new FileSystemException(src.toString(),dst.toString(),"Cannot move the DLFS root");
		if (dst.getNameCount()==0) throw new FileAlreadyExistsException(dst.toString());

		CVMLong operationTime=currentTimestamp();
		try {
			rootCursor.updateAndGet(root->{
				AVector<ACell> sourceNode=resolveValidUnchecked(root,src);
				if (sourceNode==null) throw new UncheckedIOException(new NoSuchFileException(src.toString()));
				if (src.equals(dst)) return root;
				if (DLFSNode.isDirectory(sourceNode)&&dst.startsWith(src)) {
					throw new UncheckedIOException(new FileSystemException(src.toString(),dst.toString(),"Cannot move a directory into itself"));
				}
				AVector<ACell> targetParent=requireDirectory(root,dst.getParent());
				AString targetName=dst.getCVMFileName();
				AVector<ACell> targetNode=resolveValidUnchecked(root,dst);
				if (targetNode!=null) {
					if (!replaceExisting) requireAbsentUnchecked(targetParent,targetName,dst);
					if (DLFSNode.isDirectory(targetNode)&&!DLFSNode.isEmpty(targetNode)) {
						throw new UncheckedIOException(new DirectoryNotEmptyException(dst.toString()));
					}
				}
				AVector<ACell> movedNode=copyTimes(sourceNode,operationTime,true);

				AVector<ACell> updated=DLFSNode.deleteNode(root,src,operationTime);
				return DLFSNode.updateEntry(updated,dst,movedNode,operationTime);
			});
		} catch (UncheckedIOException e) {
			throw e.getCause();
		}
	}

	/** Copies one immutable snapshot, then conditionally removes precisely that source. */
	private void transfer(DLPath source,DLPath target,boolean recursive,boolean replaceExisting,boolean move,boolean copyAttributes) throws IOException {
		DLPath src=source.toAbsolutePath().normalize();
		DLPath dst=target.toAbsolutePath().normalize();
		if (src.getNameCount()==0) throw new FileSystemException(src.toString(),dst.toString(),"Cannot transfer the DLFS root");
		if (dst.getNameCount()==0) throw new FileAlreadyExistsException(dst.toString());
		if (!(dst.getFileSystem() instanceof DLFSLocal destination)) {
			throw new FileSystemException(src.toString(),dst.toString(),"Unsupported DLFS destination implementation");
		}
		AVector<ACell> snapshot=DLFSNode.resolveValidPath(rootCursor.get(),src);
		if (snapshot==null) throw new NoSuchFileException(src.toString());
		// Reject obvious target failures before copying any potentially large stored graph.
		try { checkTransferTarget(destination.rootCursor.get(),dst,replaceExisting); }
		catch (UncheckedIOException e) { throw e.getCause(); }
		CVMLong destinationTime=destination.currentTimestamp();
		AVector<ACell> copied=copyTimes(snapshot,destinationTime,copyAttributes);
		if (DLFSNode.isDirectory(copied) && !recursive) {
			copied=DLFSNode.withoutChildren(copied);
		}
		// Bind foreign branches to the destination store before publication. Heap-only
		// destinations need direct refs so closing the source store cannot lose data.
		if (store!=destination.store) {
			copied=Cells.persist(copied,(destination.store==null)?new MemoryStore():destination.store);
		}
		AVector<ACell> prepared=copied;
		try {
			destination.rootCursor.updateAndGet(root->{
				checkTransferTarget(root,dst,replaceExisting);
				return DLFSNode.updateEntry(root,dst,prepared,destinationTime);
			});
			if (move) {
				CVMLong deletionTime=currentTimestamp();
				rootCursor.updateAndGet(root->{
					if (!Utils.equals(snapshot,resolveValidUnchecked(root,src))) {
						throw new UncheckedIOException(new FileSystemException(src.toString(),dst.toString(),
							"Source changed during move; destination copy retained"));
					}
					return DLFSNode.deleteNode(root,src,deletionTime);
				});
			}
		} catch (UncheckedIOException e) {
			throw e.getCause();
		}
	}

	private static void checkTransferTarget(AVector<ACell> root,DLPath dst,boolean replaceExisting) {
		AVector<ACell> parent=requireDirectory(root,dst.getParent());
		AVector<ACell> existing=resolveValidUnchecked(root,dst);
		if (existing==null) return;
		if (!replaceExisting) requireAbsentUnchecked(parent,dst.getCVMFileName(),dst);
		if (DLFSNode.isDirectory(existing) && !DLFSNode.isEmpty(existing)) {
			throw new UncheckedIOException(new DirectoryNotEmptyException(dst.toString()));
		}
	}

	private static AVector<ACell> copyTimes(AVector<ACell> node,CVMLong time,boolean preserve) {
		return DLFSNode.withMTime(node,preserve?DLFSNode.getMTime(node):time).assoc(DLFSNode.POS_UTIME,time);
	}

	private static AVector<ACell> requireDirectory(AVector<ACell> rootNode, DLPath path) {
		AVector<ACell> node=resolveValidUnchecked(rootNode,path);
		if (node==null) throw new UncheckedIOException(new NoSuchFileException(path.toString()));
		if (!DLFSNode.isDirectory(node)) throw new UncheckedIOException(new NotDirectoryException(path.toString()));
		return node;
	}

	@Override
	public synchronized AVector<ACell> updateNode(DLPath dir, AVector<ACell> newNode) throws IOException {
		return updateNode(dir,newNode,currentTimestamp());
	}

	/** Updates a node using one timestamp already resolved for the logical operation. */
	synchronized AVector<ACell> updateNode(DLPath dir, AVector<ACell> newNode,
			CVMLong operationTime) throws IOException {
		if (newNode!=null && !DLFSNode.isValidNodeShallow(newNode)) throw new IllegalArgumentException("Invalid replacement DLFS node");
		try {
			rootCursor.updateAndGet(rootNode->{
				DLPath parent=dir.toAbsolutePath().normalize().getParent();
				if (parent!=null) {
					AVector<ACell> parentNode=resolveValidUnchecked(rootNode,parent);
					if (parentNode==null) throw new UncheckedIOException(new NoSuchFileException(parent.toString()));
					if (!DLFSNode.isDirectory(parentNode)) throw new UncheckedIOException(new NotDirectoryException(parent.toString()));
				}
				return DLFSNode.updateNode(rootNode,dir,newNode,operationTime);
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
		// The local filesystem merges values. Hosted components own incoming admission.
		rootCursor.updateAndGet(own -> DLFSLattice.INSTANCE.merge(own,other));
	}

	/** Creates a forked view sharing this filesystem's store, if any. */
	@Override
	public DLFSLocal fork() {
		var working=new LatticeComponent<>(DLFSLattice.INSTANCE,rootCursor,getContext());
		return new WorkingFileSystem(provider(),uriPath,working,store);
	}

	/** NIO adapter whose policy belongs to its publication component. */
	private static final class WorkingFileSystem extends DLFSLocal {
		private final LatticeComponent<AVector<ACell>> working;

		WorkingFileSystem(DLFSProvider provider,String uriPath,
				LatticeComponent<AVector<ACell>> working,AStore store) {
			super(provider,uriPath,working.cursor(),store);
			this.working=working;
		}

		@Override public LatticeContext getContext() { return working.getContext(); }
		@Override public void setContext(LatticeContext context) {
			working.setContext(java.util.Objects.requireNonNull(context));
		}

		@Override public void sync() { working.sync(); }
	}

	@Override
	public void sync() {
		// A live view is already updated. Only independent working views publish.
	}

	/** Creates an independent snapshot of the current state, checkpointing into the same store, if any. */
	@Override
	public DLFSLocal clone() {
		AVector<ACell> root=rootCursor.get();
		AForkableCursor<AVector<ACell>> cursor=Cursors.root(root);
		DLFSLocal result=new DLFSLocal(provider(),uriPath,cursor,store);
		result.setContext(getContext());
		return result;
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
