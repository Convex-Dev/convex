package convex.core.examples;

import java.io.IOException;
import java.util.Random;

import convex.core.data.ABlob;
import convex.core.data.Blobs;
import convex.core.data.Cells;
import convex.core.data.Hash;
import convex.core.data.Ref;
import convex.core.store.Stores;
import convex.etch.EtchStore;

/**
 * A test application that just writes a lot of random Blobs to Etch in order to test
 * out large database sizes.
 */
public class EtchStressTest {

	public static void main(String[] args) throws IOException {
		Random r=new Random(123);
		EtchStore store=EtchStore.createTemp();
		Stores.setCurrent(store);
		long rc=0;
		long STEP=10000000; // threshold at which to print a status line
		
		while (true) {
			ABlob b=Blobs.createRandom(r,10000);
			Hash h=b.getHash();
			
			b=Cells.persist(b);
			
			Ref<ABlob> rb=store.refForHash(h);
			if (rb==null) {
				throw new Error("Lost a blob!");
			}
			
			long dl=store.getEtch().getDataLength();
			long dc=(dl/STEP) * STEP;
			if (dc>rc) {
				System.out.println("Length: "+dl);
				rc=dc;
			}
		}
	}

}
