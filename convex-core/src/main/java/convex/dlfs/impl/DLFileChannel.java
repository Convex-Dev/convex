package convex.dlfs.impl;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.StandardOpenOption;
import java.util.Set;

import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Blobs;
import convex.dlfs.DLFSNode;
import convex.dlfs.DLFileSystem;
import convex.dlfs.DLPath;

public class DLFileChannel implements SeekableByteChannel {

	private boolean isOpen=true;
	private boolean readOnly=true;
	private long position=0;
	private DLPath path;
	private DLFileSystem fileSystem;
	
	private DLFileChannel(DLFileSystem fs, DLPath path) {
		this.fileSystem=fs;
		this.path=path;
	}
	
	public static DLFileChannel create(DLFileSystem fs, Set<? extends OpenOption> options, DLPath path) throws IOException {
		AVector<ACell> node= fs.getNode(path);
		
		boolean append=false;
		boolean truncate=false;
		boolean readOnly=true;
		if (options!=null) {
			if (options.contains(StandardOpenOption.WRITE)) {
				readOnly=false;
			}
			if (options.contains(StandardOpenOption.CREATE_NEW)) {
				if (node!=null) {
					// can create over a tombstone
					if(!DLFSNode.isTombstone(node)) {
						throw new FileAlreadyExistsException(path.toString());
					}
				}
			}
			if (options.contains(StandardOpenOption.APPEND)) {
				append=true;
			}
			if (options.contains(StandardOpenOption.TRUNCATE_EXISTING)) {
				truncate=true;
			}
		}
		
		if ((node==null)||DLFSNode.isTombstone(node)) {
			if (readOnly) throw new NoSuchFileException(path.toString());
			node=fs.createFile(path);
		} else {
			if (DLFSNode.getData(node)==null) throw new NoSuchFileException(path.toString());
		}
		DLFileChannel fc=new DLFileChannel(fs,path);
		fc.readOnly=readOnly;
		if (truncate) fc.truncate(0);
		if (append) fc.position=DLFSNode.getData(node).count();
		
		return fc;
	}

	@Override
	public boolean isOpen() {
		return isOpen;
	}

	@Override
	public void close() throws IOException {
		isOpen=false;
	}

	@Override
	public int read(ByteBuffer dst) throws IOException {
		synchronized(this) {
			checkOpen();
			ABlob data=getData();
			
			// position beyond end legal, but reads register end of file
			if (position>=data.count()) return -1;
			
			int read=data.toByteBuffer(position,dst);
			position+=read;
			return read;
		}
	}

	@Override
	public int write(ByteBuffer src) throws IOException {
		synchronized(this) {
			checkOpen();
			long pos=position;
			AVector<ACell> node=getNode();
			ABlob data = DLFSNode.getData(node);
			if (data==null) throw new NoSuchFileException(path.toString());
			
			if (data.count()<pos) {
				// extend file with zeros to start at new position
				// ZeroBlob implementation makes this relatively cheap
				data=data.append(Blobs.createZero(pos-data.count()));
			}
			
			Blob b=Blob.fromByteBuffer(src);
			long n=b.count();
			ABlob newData=data.replaceSlice(pos,b);
			
			// position after replaced slice
			position=pos+n;
			
			if (newData!=data) {
				AVector<ACell> newNode=node.assoc(DLFSNode.POS_DATA, newData);
				updateNode(newNode);
			}
			
			return (int)n;
		}
	}

	protected AVector<ACell> updateNode(AVector<ACell> newNode) throws IOException {
		if (readOnly) {
			throw new NonWritableChannelException();
		}
		return fileSystem.updateNode(path, newNode);
	}

	@Override
	public long position() throws IOException {
		checkOpen();
		return position;
	}

	@Override
	public SeekableByteChannel position(long newPosition) throws IOException {
		checkOpen();
		if (newPosition<0) throw new IllegalArgumentException("Negative position");
		position=newPosition;
		return this;
	}

	@Override
	public long size() throws IOException {
		synchronized(fileSystem) {
			checkOpen();
			ABlob data=getData();
			return data.count();
		}
	}

	private void checkOpen() throws ClosedChannelException {
		if (!isOpen) {
			throw new ClosedChannelException();
		}
	}

	/**
	 * Gets data, or throws if not a data file
	 * @return
	 * @throws NoSuchFileException
	 */
	private ABlob getData() throws NoSuchFileException {
		AVector<ACell> node=getNode();
		return DLFSNode.getData(node);
	}

	/**
	 * Gets a node, or throws if not an existent node
	 * @return
	 * @throws NoSuchFileException
	 */
	private AVector<ACell> getNode() throws NoSuchFileException {
		AVector<ACell> node= fileSystem.getNode(path);
		if (node==null) throw new NoSuchFileException(path.toString());
		return node;
	}

	@Override
	public SeekableByteChannel truncate(long size) throws IOException {
		checkOpen();
		if (size<0) throw new IllegalArgumentException("Negative position");

		synchronized(fileSystem) {
			AVector<ACell> node=getNode();
			ABlob data = DLFSNode.getData(node);
			if (data==null) throw new NoSuchFileException(path.toString());
			long newSize=Math.min(size, data.count());
			ABlob newData=data.slice(0, newSize);
			if (newData!=data) {
				AVector<ACell> newNode=node.assoc(DLFSNode.POS_DATA, newData);
				updateNode(newNode);
			}
			position=0;
		}
		return this;
	}



}
