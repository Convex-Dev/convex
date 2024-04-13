package convex.dlfs.impl;

import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.dlfs.DLFSNode;
import convex.dlfs.DLFSProvider;
import convex.dlfs.DLFileSystem;
import convex.dlfs.DLPath;

public class DLFSLocal extends DLFileSystem {
	
	AVector<ACell> root=DLFSNode.EMPTY_DIRECTORY;
	
	public DLFSLocal(DLFSProvider dlfsProvider, String uriPath) {
		super(dlfsProvider,uriPath);
	}

	public static DLFileSystem create(DLFSProvider provider) {
		return new DLFSLocal(provider,null);
	}

	@Override
	protected AVector<ACell> getNode(DLPath path) {
		AVector<ACell> result=DLFSNode.navigate(root,path);
		return result;
	}

}
