package convex.examples;

import java.io.FileOutputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

import convex.api.Convex;
import convex.core.cvm.State;
import convex.core.data.ACell;
import convex.core.data.Blob;
import convex.core.data.Format;
import convex.core.data.Hash;
import convex.core.store.MemoryStore;

/**
 * Demo that acquires the genesis state by hash from a live peer and writes it
 * in CAD3 multi-cell format.
 *
 * Usage: AcquireGenesis [destination]
 * Default destination: genesis.cad3
 */
public class AcquireGenesis {

	/** Genesis state hash */
	static final String GENESIS_HASH = "b0e44f2a645abfa539f5b96b7a0eabb0f902866feaff0f7c12d1213e02333f13";

	public static void main(String[] args) throws Exception {
		InetSocketAddress host = new InetSocketAddress("peer.convex.live", 18888);
		Hash hash = Hash.fromHex(GENESIS_HASH);

		System.out.println("Connecting to " + host + "...");
		Convex convex = Convex.connect(host, null, null);
		convex.setStore(new MemoryStore());

		System.out.println("Acquiring genesis state " + hash + "...");
		ACell cell = convex.acquire(hash).get(60000, TimeUnit.MILLISECONDS);

		if (!(cell instanceof State)) {
			System.err.println("Acquired cell is not a State: " + cell.getClass().getSimpleName());
			convex.close();
			return;
		}
		State state = (State) cell;
		System.out.println("State acquired: " + state.getHash());

		Blob encoded = Format.encodeMultiCell(state, true);
		System.out.println("Encoded multi-cell: " + encoded.count() + " bytes");

		String path = (args.length > 0) ? args[0] : "genesis.cad3";
		try (FileOutputStream fos = new FileOutputStream(path)) {
			fos.write(encoded.getBytes());
		}
		System.out.println("Written to " + path);

		convex.close();
	}
}
