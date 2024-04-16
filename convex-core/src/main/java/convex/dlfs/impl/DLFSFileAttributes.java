package convex.dlfs.impl;

import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;

import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.dlfs.DLFSNode;

public class DLFSFileAttributes implements BasicFileAttributes {

	private AVector<ACell> node;

	public DLFSFileAttributes(AVector<ACell> node) {
		this.node=node;
	}

	public static DLFSFileAttributes create(AVector<ACell> node) {
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
		return DLFSNode.isRegularFile(node);
	}

	@Override
	public boolean isDirectory() {
		return DLFSNode.isDirectory(node);
	}

	@Override
	public boolean isSymbolicLink() {
		return false;
	}

	@Override
	public boolean isOther() {
		return DLFSNode.isTombstone(node);
	}

	@Override
	public long size() {
		ABlob blob=DLFSNode.getData(node);
		if (blob!=null) return blob.count();
		return 0;
	}

	@Override
	public Object fileKey() {
		// TODO Auto-generated method stub
		return null;
	}

}
