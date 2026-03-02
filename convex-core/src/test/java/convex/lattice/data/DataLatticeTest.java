package convex.lattice.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.Hash;
import convex.core.data.Index;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;

public class DataLatticeTest {

	@Test
	public void testZeroIsEmpty() {
		DataLattice lattice = DataLattice.INSTANCE;
		Index<Hash, ACell> zero = lattice.zero();
		
		// Zero datalattice should be an empty index
		assertSame(Index.EMPTY, zero);
		assertEquals(0L, zero.count());
		assertTrue(zero.isEmpty());
	}

	@Test
	public void testAddValues() {
		DataLattice lattice = DataLattice.INSTANCE;
		Index<Hash, ACell> index = lattice.zero();
		
		// Create test values
		ACell value1 = CVMLong.ONE;
		ACell value2 = CVMLong.TWO;
		ACell value3 = RT.cvm(42L);
		
		// Get hashes for values
		Hash hash1 = Hash.get(value1);
		Hash hash2 = Hash.get(value2);
		Hash hash3 = Hash.get(value3);
		
		// Add values to index, indexed by hash
		index = index.assoc(hash1, value1);
		index = index.assoc(hash2, value2);
		index = index.assoc(hash3, value3);
		
		// Verify values can be retrieved by hash
		assertEquals(value1, index.get(hash1));
		assertEquals(value2, index.get(hash2));
		assertEquals(value3, index.get(hash3));
		
		// Verify count
		assertEquals(3L, index.count());
		
		// Verify all values are present
		assertTrue(index.containsValue(value1));
		assertTrue(index.containsValue(value2));
		assertTrue(index.containsValue(value3));
	}

	@Test
	public void testMergeCreatesUnion() {
		DataLattice lattice = DataLattice.INSTANCE;
		
		// Create test values
		ACell value1 = CVMLong.ONE;
		ACell value2 = CVMLong.TWO;
		ACell value3 = RT.cvm(42L);
		ACell value4 = RT.cvm(100L);
		
		// Get hashes
		Hash hash1 = Hash.get(value1);
		Hash hash2 = Hash.get(value2);
		Hash hash3 = Hash.get(value3);
		Hash hash4 = Hash.get(value4);
		
		// Create first index with value1 and value2
		Index<Hash, ACell> index1 = lattice.zero();
		index1 = index1.assoc(hash1, value1);
		index1 = index1.assoc(hash2, value2);
		
		// Create second index with value2 (overlapping) and value3, value4
		Index<Hash, ACell> index2 = lattice.zero();
		index2 = index2.assoc(hash2, value2);
		index2 = index2.assoc(hash3, value3);
		index2 = index2.assoc(hash4, value4);
		
		// Merge should create union of values
		Index<Hash, ACell> merged = lattice.merge(index1, index2);
		
		assertNotNull(merged);
		
		// Verify all values are present in merged index
		assertEquals(value1, merged.get(hash1));
		assertEquals(value2, merged.get(hash2));
		assertEquals(value3, merged.get(hash3));
		assertEquals(value4, merged.get(hash4));
		
		// Verify count is 4 (union of all unique values)
		assertEquals(4L, merged.count());
		
		// Verify all values are present
		assertTrue(merged.containsValue(value1));
		assertTrue(merged.containsValue(value2));
		assertTrue(merged.containsValue(value3));
		assertTrue(merged.containsValue(value4));
	}

	@Test
	public void testMergeWithZero() {
		DataLattice lattice = DataLattice.INSTANCE;
		
		ACell value1 = CVMLong.ONE;
		Hash hash1 = Hash.get(value1);
		
		Index<Hash, ACell> index = lattice.zero();
		index = index.assoc(hash1, value1);
		
		// Merge with zero should return the same index
		Index<Hash, ACell> zero = lattice.zero();
		Index<Hash, ACell> merged1 = lattice.merge(index, zero);
		Index<Hash, ACell> merged2 = lattice.merge(zero, index);
		
		assertEquals(index, merged1);
		assertEquals(index, merged2);
		assertEquals(value1, merged1.get(hash1));
		assertEquals(value1, merged2.get(hash1));
	}
}
