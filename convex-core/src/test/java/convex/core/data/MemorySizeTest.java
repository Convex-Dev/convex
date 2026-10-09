package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.Constants;

public class MemorySizeTest {

	@Test
	@SuppressWarnings({"unchecked", "rawtypes"})
	public void testOverflowUnwindsToRoot() {
		boolean[] calculatingRoot={false};
		int[] attempts={0};
		ACell child=new VectorLeaf<ACell>(new Ref[0]) {
			@Override
			protected long calcMemorySize() {
				if (++attempts[0]==1) throw new StackOverflowError();
				// Recovery must run after the enclosing recursive calculation exits.
				assertFalse(calculatingRoot[0]);
				return super.calcMemorySize();
			}
		};
		ACell root=new VectorLeaf<ACell>(new Ref[] {child.getRef()}) {
			@Override
			protected long calcMemorySize() {
				calculatingRoot[0]=true;
				try {
					return super.calcMemorySize();
				} finally {
					calculatingRoot[0]=false;
				}
			}
		};
		assertEquals(0,root.getMemorySize());
		assertEquals(2,attempts[0]);
		assertEquals(0,root.getMemorySize());
		assertEquals(2,attempts[0]);
	}

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
	public void testMultipleDeepBranches() {
		Blob leaf=Blob.wrap(new byte[256]);
		long leafSize=leaf.getEncodingLength()+Constants.MEMORY_OVERHEAD;
		ACell first=leaf;
		ACell second=leaf;
		int depth=20000;
		for (int i=0; i<depth; i++) {
			first=Vectors.create(first,leaf,leaf,leaf,leaf);
			second=Vectors.create(second,leaf,leaf,leaf,leaf);
		}
		ACell root=Vectors.create(first,second,first,null);
		long vectorSize=2+5*Ref.INDIRECT_ENCODING_LENGTH+Constants.MEMORY_OVERHEAD;
		long branchSize=(1+4L*depth)*leafSize+depth*vectorSize;
		assertEquals(3*branchSize,root.getMemorySize());
		assertEquals(branchSize,first.memorySize);
		assertEquals(branchSize,second.memorySize);
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
