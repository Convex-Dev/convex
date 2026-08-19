package convex.dlfs.test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.BlobTree;
import convex.core.data.Blobs;
import convex.core.data.prim.CVMLong;
import convex.core.util.Utils;
import convex.dlfs.DLFSServer;
import convex.lattice.fs.DLFSNode;
import convex.lattice.fs.DLFileSystem;
import convex.lattice.fs.impl.DLFSLocal;
import convex.lattice.fs.DLPath;

/**
 * One-off constrained-heap smoke check for structural WebDAV copies and moves.
 *
 * <p>This is deliberately not a JUnit test: it creates a sparse file much larger
 * than the permitted heap and is intended to run explicitly before a release.</p>
 */
public final class DLFSWebDAVLargeFileSmoke {
	private static final long DEFAULT_FILE_SIZE=1L<<30; // 1 GiB
	private static final long MAX_EXPECTED_HEAP=192L<<20;

	private DLFSWebDAVLargeFileSmoke() {
	}

	public static void main(String[] args) throws Exception {
		long fileSize=(args.length==0)?DEFAULT_FILE_SIZE:Long.parseLong(args[0]);
		long heap=Runtime.getRuntime().maxMemory();
		if (heap>MAX_EXPECTED_HEAP) {
			throw new IllegalStateException("Run with -Xmx128m (observed max heap: "+heap+")");
		}
		if (fileSize<=heap) {
			throw new IllegalArgumentException("Sparse file size must exceed the maximum heap");
		}

		try (DLFSServer server=DLFSServer.createEphemeral().setMaxRequestSize(fileSize+1)) {
			if (!server.getDriveManager().createDrive(null,"large-copy")) {
				throw new IllegalStateException("Could not create smoke-test drive");
			}
			DLFileSystem fs=(DLFileSystem)server.getDriveManager().getDrive(null,"large-copy");
			DLPath source=(DLPath)fs.getPath("/source.bin");
			Files.createFile(source);

			ABlob sparse=Blobs.createZero(fileSize);
			if (!(sparse instanceof BlobTree)) throw new AssertionError("Expected a BlobTree source");
			DLFSLocal local=(DLFSLocal)fs;
			CVMLong timestamp=CVMLong.create(Utils.getCurrentTimestamp());
			local.getCursor().setContext(local.getCursor().getContext().withTimestamp(timestamp));
			AVector<ACell> sourceNode=DLFSNode.createEmptyFile(timestamp)
				.assoc(DLFSNode.POS_DATA,sparse);
			fs.updateNode(source,sourceNode);

			server.start(0);
			String base="http://localhost:"+server.getPort()+"/dlfs/large-copy/";
			HttpClient client=HttpClient.newHttpClient();
			request(client,"COPY",base+"source.bin",base+"copy.bin",201);
			DLPath copy=(DLPath)fs.getPath("/copy.bin");
			if (DLFSNode.getData(fs.getNode(copy))!=sparse) {
				throw new AssertionError("COPY did not structurally share the source BlobTree");
			}

			request(client,"MOVE",base+"copy.bin",base+"moved.bin",201);
			DLPath moved=(DLPath)fs.getPath("/moved.bin");
			if (Files.exists(copy)) throw new AssertionError("MOVE retained the source path");
			if (DLFSNode.getData(fs.getNode(moved))!=sparse) {
				throw new AssertionError("MOVE did not structurally share the source BlobTree");
			}

			System.out.println("PASS: structurally copied and moved "+fileSize
				+" bytes with max heap "+heap);
		}
	}

	private static void request(HttpClient client, String method, String source,
			String destination, int expectedStatus) throws Exception {
		HttpRequest request=HttpRequest.newBuilder(URI.create(source))
			.method(method,HttpRequest.BodyPublishers.noBody())
			.header("Destination",destination)
			.build();
		HttpResponse<Void> response=client.send(request,HttpResponse.BodyHandlers.discarding());
		if (response.statusCode()!=expectedStatus) {
			throw new AssertionError(method+" returned "+response.statusCode()
				+", expected "+expectedStatus);
		}
	}
}
