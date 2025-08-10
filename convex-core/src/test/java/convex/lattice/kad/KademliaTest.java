package convex.lattice.kad;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import convex.core.data.Blob;
import convex.core.data.Blobs;

public class KademliaTest {

	@Test public void testDistance() {
		Blob ba=Blob.fromHex("cafebabe00000000");
		Blob bb=Blob.fromHex("cafebabe00000001");
		Blob bc=Blob.fromHex("cafebabe10000001");
		Blob bx=Blob.fromHex("cafebabe10000001ff");
		Blob bz=Blob.fromHex("0000000000000000");
		
		assertEquals(0,Kademlia.distance(ba,ba));
		assertEquals(0,Kademlia.distance(bb,bb));
		assertEquals(0x10000001,Kademlia.distance(ba,bc));
		assertEquals(0xcafebabe00000001l,Kademlia.distance(bb,bz));
		
		// Tests with extra bytes
		assertEquals(0,Kademlia.distance(bx,bc));
		assertEquals(0,Kademlia.distance(bx,bx));

		// Tests with bytes
		assertThrows (IllegalArgumentException.class,()->Kademlia.distance(Blobs.empty(),Blob.SINGLE_ZERO));

	}
}
