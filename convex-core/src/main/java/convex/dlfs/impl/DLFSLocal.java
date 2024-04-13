package convex.dlfs.impl;

import convex.core.data.ACell;
import convex.dlfs.DLFSNode;
import convex.dlfs.DLFSProvider;
import convex.dlfs.DLFileSystem;

public class DLFSLocal extends DLFileSystem {
	
	ACell root=DLFSNode.EMPTY_DIRECTORY;
	
	protected DLFSLocal(DLFSProvider dlfsProvider, String uriPath) {
		super(dlfsProvider,uriPath);
	}

}
