package convex.examples;

import java.io.FileOutputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

import convex.api.Convex;
import convex.core.Result;
import convex.core.cpos.Belief;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Format;
import convex.core.data.Hash;
import convex.core.lang.RT;

/**
 * Acquires the current Belief from a live peer and writes it
 * in CAD3 multi-cell format.
 *
 * Usage: AcquireBelief [destination]
 * Default destination: belief-[timestamp].cad3
 */
public class AcquireBelief {

	public static void main(String[] args) throws Exception {
		InetSocketAddress host = new InetSocketAddress("peer.convex.live", 18888);

		System.out.println("Connecting to " + host + "...");
		Convex convex = Convex.connect(host, null, null);

		System.out.println("Requesting status...");
		Result status = convex.requestStatusSync(15000);
		
		AVector<ACell> statusVector = RT.ensureVector(status.getValue());
		System.out.println("Status: " + statusVector);
		Hash beliefHash = RT.ensureHash(statusVector.get(0));
		if (beliefHash == null) {
			System.err.println("Could not get belief hash from status");
			convex.close();
			return;
		}

		System.out.println("Acquiring belief " + beliefHash + "...");
		ACell cell = convex.acquire(beliefHash).get(60000, TimeUnit.MILLISECONDS);

		if (!(cell instanceof Belief)) {
			System.err.println("Acquired cell is not a Belief: " + cell.getClass().getSimpleName());
			convex.close();
			return;
		}
		Belief belief = (Belief) cell;
		System.out.println("Belief acquired: " + belief.getHash());

		Blob encoded = Format.encodeMultiCell(belief, true);
		System.out.println("Encoded multi-cell: " + encoded.count() + " bytes");

		String path = (args.length > 0) ? args[0] : "belief-" + System.currentTimeMillis() + ".cad3";
		try (FileOutputStream fos = new FileOutputStream(path)) {
			fos.write(encoded.getBytes());
		}
		System.out.println("Written to " + path);

		convex.close();
	}
}
