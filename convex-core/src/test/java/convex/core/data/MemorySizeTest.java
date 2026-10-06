package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.Constants;

public class MemorySizeTest {

	@Test
	public void testDeepMemorySize() {
		Blob leaf=Blob.wrap(new byte[256]);
		long leafSize=leaf.getEncodingLength()+Constants.MEMORY_OVERHEAD;
		ACell root=leaf;
		int depth=20000;
		for (int i=0; i<depth; i++) {
			// Five non-embedded children keep every vector non-embedded too.
			root=Vectors.create(root,leaf,leaf,leaf,leaf);
		}
		assertEquals(-1,root.memorySize);
		long vectorSize=2+5*Ref.INDIRECT_ENCODING_LENGTH+Constants.MEMORY_OVERHEAD;
		long expected=(1+4L*depth)*leafSize+depth*vectorSize;
		assertEquals(expected,root.getMemorySize());
		assertEquals(expected,root.getMemorySize());
	}

	@Test
	public void testEmbeddedParentWithSharedBranch() {
		Blob leaf=Blob.wrap(new byte[256]);
		AVector<ACell> root=Vectors.create(leaf,null,leaf);
		long expected=2L*(leaf.getEncodingLength()+Constants.MEMORY_OVERHEAD);
		assertEquals(expected,root.getMemorySize());
		assertTrue(root.isEmbedded());
	}

	@Test
	public void testSharedMemorySizeSaturates() {
		ACell root=Blob.wrap(new byte[256]);
		for (int i=0; i<80; i++) {
			root=Vectors.create(root,root);
		}
		// Each reference is charged, while each shared cell is calculated once.
		assertEquals(Long.MAX_VALUE,root.getMemorySize());
	}
}
