package convex.dlfs.impl;

import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;

import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.dlfs.DLFSNode;

class DLFSFileAttributes implements BasicFileAttributes {

	private AVector<ACell> node;

	public DLFSFileAttributes(AVector<ACell> node) {
		this.node=node;
	}

	public DLFSFileAttributes create(AVector<ACell> node) {
		return new DLFSFileAttributes(node);
	}
	
	@Override
	public FileTime lastModifiedTime() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public FileTime lastAccessTime() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public FileTime creationTime() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public boolean isRegularFile() {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean isDirectory() {
		// TODO Auto-generated method stub
		return DLFSNode.isDirector(node);
	}

	@Override
	public boolean isSymbolicLink() {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean isOther() {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public long size() {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public Object fileKey() {
		// TODO Auto-generated method stub
		return null;
	}

}
