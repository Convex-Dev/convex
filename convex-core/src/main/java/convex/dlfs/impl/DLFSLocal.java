package convex.dlfs.impl;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.DirectoryStream.Filter;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.util.Set;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Cells;
import convex.core.data.Hash;
import convex.core.data.prim.CVMLong;
import convex.dlfs.DLFS;
import convex.dlfs.DLFSNode;
import convex.dlfs.DLFSProvider;
import convex.dlfs.DLFileSystem;
import convex.dlfs.DLPath;

/**
 * Local DLFS Drive implementation
 */
public class DLFSLocal extends DLFileSystem {
	
	AVector<ACell> rootNode;
	
	public DLFSLocal(DLFSProvider dlfsProvider, String uriPath, AVector<ACell> rootNode) {
		super(dlfsProvider,uriPath,DLFSNode.getUTime(rootNode));
		this.rootNode=rootNode;
	}

	public static DLFSLocal create(DLFSProvider provider) {
		return new DLFSLocal(provider,null,DLFSNode.createDirectory(CVMLong.ZERO));
	}

	@Override
	public AVector<ACell> getNode(DLPath path) {
		AVector<ACell> result=DLFSNode.navigate(rootNode,path);
		return result;
	}

	@Override
	protected DLDirectoryStream newDirectoryStream(DLPath dir, Filter<? super Path> filter) {
		AVector<ACell> result=DLFSNode.navigate(rootNode,dir);
		return DLDirectoryStream.create(dir,result);
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
		AVector<ACell> parentNode=DLFSNode.navigate(rootNode, parent);
		if (parentNode==null) {
			throw new FileNotFoundException(parent.toString());
		}
		if (DLFSNode.getDirectoryEntries(parentNode).containsKey(name)) {
			throw new FileAlreadyExistsException(dir.toString());
		}
		updateNode(dir,DLFSNode.createDirectory(getTimestamp()));
		return dir;
	}
	
	@Override
	public synchronized AVector<ACell> createFile(DLPath path) throws IOException {
		AString name=path.getCVMFileName();
		path=path.toAbsolutePath();
		DLPath parent=path.getParent();
		if (parent==null) throw new FileAlreadyExistsException(path.toString()); // trying to create root
		AVector<ACell> parentNode=DLFSNode.navigate(rootNode, parent);
		if (parentNode==null) {
			throw new FileNotFoundException("Parent directory does not exist: "+parent.toString());
		}
		AVector<ACell> oldNode=DLFSNode.getDirectoryEntries(parentNode).get(name);
		if (oldNode!=null) {
			if (!DLFSNode.isTombstone(oldNode)) {
				throw new FileAlreadyExistsException(name.toString());
			}
		}
		AVector<ACell> newNode=DLFSNode.createEmptyFile(getTimestamp());
		updateNode(path,newNode);
		return newNode;
	}
	

	@Override
	public synchronized void delete(DLPath path) throws IOException {
		path=path.toAbsolutePath();
		if (path.getNameCount()==0) {
			throw new IOException("Can't delete DLFS Root node");
		}
		
		// Check file actually exists
		AVector<ACell> node=getNode(path);
		if (node==null) throw new NoSuchFileException(path.toString());
		
		// Check it it empty, if a directory
		AHashMap<AString, AVector<ACell>> entries = DLFSNode.getDirectoryEntries(node);
		if ((entries!=null)&&(!entries.isEmpty())) throw new DirectoryNotEmptyException(path.toString());
		
		updateNode(path,DLFSNode.createTombstone(getTimestamp()));
	}

	@Override
	public synchronized AVector<ACell> updateNode(DLPath dir, AVector<ACell> newNode) {
		rootNode=DLFSNode.updateNode(rootNode,dir,newNode,getTimestamp());
		return newNode;
	}

	@Override
	protected void checkAccess(DLPath path) throws IOException {
		AVector<ACell> node=DLFSNode.navigate(rootNode,path);
		if ((node==null)||(DLFSNode.isTombstone(node))) {
			throw new NoSuchFileException(path.toString());
		}
	}

	@Override
	public Hash getRootHash() {
		return Cells.getHash(rootNode);
	}

	@Override
	public void merge(AVector<ACell> other) {
		AVector<ACell> merged=DLFSNode.merge(rootNode,other,getTimestamp());
		rootNode=merged;
	}


	@Override 
	public DLFSLocal clone() {
		DLFSLocal result=new DLFSLocal(provider(),uriPath,rootNode);
		return result;
	}
}
