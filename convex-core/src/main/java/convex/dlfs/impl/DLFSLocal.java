package convex.dlfs.impl;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryStream.Filter;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.util.Set;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.dlfs.DLFS;
import convex.dlfs.DLFSNode;
import convex.dlfs.DLFSProvider;
import convex.dlfs.DLFileSystem;
import convex.dlfs.DLPath;

public class DLFSLocal extends DLFileSystem {
	
	AVector<ACell> rootNode=DLFSNode.EMPTY_DIRECTORY;
	
	public DLFSLocal(DLFSProvider dlfsProvider, String uriPath) {
		super(dlfsProvider,uriPath);
	}

	public static DLFileSystem create(DLFSProvider provider) {
		return new DLFSLocal(provider,null);
	}

	@Override
	protected AVector<ACell> getNode(DLPath path) {
		AVector<ACell> result=DLFSNode.navigate(rootNode,path);
		return result;
	}

	@Override
	public DLDirectoryStream newDirectoryStream(DLPath dir, Filter<? super Path> filter) {
		AVector<ACell> result=DLFSNode.navigate(rootNode,dir);
		return DLDirectoryStream.create(dir,result);
	}

	@Override
	public SeekableByteChannel newByteChannel(DLPath path, Set<? extends OpenOption> options, FileAttribute<?>[] attrs) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	protected synchronized DLPath createDirectory(DLPath dir, FileAttribute<?>[] attrs) throws IOException {
		AString name=dir.getCVMFileName();
		if (name==null) throw new FileAlreadyExistsException(DLFS.ROOT_STRING);
		DLPath parent=dir.getParent();
		if (parent==null) throw new FileAlreadyExistsException(dir.toString());
		AVector<ACell> parentNode=DLFSNode.navigate(rootNode, parent);
		if (parentNode==null) throw new FileNotFoundException(parent.toString());
		if (DLFSNode.getDirectoryEntries(parentNode).containsKey(name)) {
			throw new FileAlreadyExistsException(dir.toString());
		}
		rootNode=DLFSNode.updateNode(rootNode,dir,DLFSNode.EMPTY_DIRECTORY);
		return dir;
	}

}
