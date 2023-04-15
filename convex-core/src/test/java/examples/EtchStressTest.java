package examples;

import java.util.Random;

import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.Blobs;
import convex.core.store.Stores;
import etch.EtchStore;

public class EtchStressTest {

	public static void main(String[] args) {
		Random r=new Random(123);
		EtchStore store=EtchStore.createTemp();
		Stores.setCurrent(store);
		long rc=0;
		long STEP=100000000;
		
		while (true) {
			ABlob b=Blobs.createRandom(r,10000);
			
			b=ACell.createPersisted(b).getValue();
			
			long dl=store.getEtch().getDataLength();
			long dc=(dl/STEP) * dl;
			if (dc>rc) {
				System.out.println("Length: "+dl);
				rc=dc;
			}
		}
	}

}
