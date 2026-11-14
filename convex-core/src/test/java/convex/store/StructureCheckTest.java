package convex.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import convex.core.Constants;
import convex.core.cpos.Belief;
import convex.core.cpos.Block;
import convex.core.cpos.Order;
import convex.core.cvm.CVMTag;
import convex.core.cvm.Keywords;
import convex.core.cvm.Syntax;
import convex.core.cvm.transactions.ATransaction;
import convex.core.cvm.transactions.Invoke;
import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Cells;
import convex.core.data.DenseRecord;
import convex.core.data.Maps;
import convex.core.data.Ref;
import convex.core.data.SignedData;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.InvalidStructureException;
import convex.core.init.InitTest;
import convex.core.store.AStore;
import convex.core.store.MemoryStore;
import convex.etch.EtchStore;

/**
 * A store records a cell as persisted only once the cell has been checked against
 * each of its children (CAD048). These tests decode cells that are each correctly
 * encoded but inconsistent with a child, as a peer could send them, and persist them.
 */
public class StructureCheckTest {

	static Stream<AStore> stores() throws IOException {
		return Stream.of(new MemoryStore(), EtchStore.createTemp("structure-check"));
	}

	/** Vector leaf of n small integers: 2+2n bytes, so embedded */
	static String smallLeaf(int n) {
		StringBuilder sb=new StringBuilder("80").append(String.format("%02x",n));
		for (int i=1; i<=n; i++) sb.append("11").append(String.format("%02x",i));
		return sb.toString();
	}

	/** Vector leaf of n 16-byte blobs: 2+18n bytes, so a branch for n of 8 or more */
	static String bigLeaf(int n) {
		StringBuilder sb=new StringBuilder("80").append(String.format("%02x",n));
		for (int i=1; i<=n; i++) sb.append("3110").append(String.format("%032x",i));
		return sb.toString();
	}

	static String ref(ACell cell) {
		return "20"+cell.getHash().toHexString();
	}

	static void assertNotPersisted(ACell bad, AStore store) throws IOException {
		assertThrows(InvalidStructureException.class, ()->Cells.persist(bad, store));
		Ref<?> found=store.refForHash(bad.getHash());
		assertTrue((found==null)||(found.getStatus()<Ref.PERSISTED));
	}

	@ParameterizedTest
	@MethodSource("stores")
	public void testVectorTreeWithEmbeddedChildren(AStore store) throws Exception {
		// 256 elements claimed: sixteen embedded leaves, the last holding 15
		StringBuilder sb=new StringBuilder("808200");
		for (int i=0; i<15; i++) sb.append(smallLeaf(16));
		sb.append(smallLeaf(15));
		AVector<?> tree=store.decode(Blob.fromHex(sb.toString()));
		assertEquals(256,tree.count());
		assertNotPersisted(tree,store);
	}

	@ParameterizedTest
	@MethodSource("stores")
	public void testVectorTreeWithBranchChildren(AStore store) throws Exception {
		// 32 elements claimed: two branch leaves, the second holding 15
		ACell full=Cells.persist(store.decode(Blob.fromHex(bigLeaf(16))),store);
		ACell short15=Cells.persist(store.decode(Blob.fromHex(bigLeaf(15))),store);
		AVector<?> tree=store.decode(Blob.fromHex("8020"+ref(full)+ref(short15)));
		assertEquals(32,tree.count());
		assertNotPersisted(tree,store);
	}

	@ParameterizedTest
	@MethodSource("stores")
	public void testInvalidChildBelowValidParent(AStore store) throws Exception {
		// The invalid tree arrives first, staged as received data is, then a parent
		// referring to it is persisted
		ACell full=Cells.persist(store.decode(Blob.fromHex(bigLeaf(16))),store);
		ACell short15=Cells.persist(store.decode(Blob.fromHex(bigLeaf(15))),store);
		ACell tree=Cells.store(store.decode(Blob.fromHex("8020"+ref(full)+ref(short15))),store);
		ACell parent=store.decode(Blob.fromHex("8001"+ref(tree)));
		assertNotPersisted(parent,store);
		Ref<?> found=store.refForHash(tree.getHash());
		assertTrue(found.getStatus()<Ref.PERSISTED);
	}

	@ParameterizedTest
	@MethodSource("stores")
	public void testBlobTreeWithShortChild(AStore store) throws Exception {
		// 8192 bytes claimed: two branch chunks, the second holding 4095
		byte[] bytes=new byte[8192];
		for (int i=0; i<bytes.length; i++) bytes[i]=(byte)i;
		ABlob valid=Blob.wrap(bytes).toCanonical();
		Blob encoding=valid.getEncoding();
		String header=encoding.slice(0,encoding.count()-2*33).toHexString();
		ACell first=Cells.persist(Blob.wrap(bytes,0,4096),store);
		ACell short4095=Cells.persist(Blob.wrap(bytes,4096,4095),store);
		ABlob tree=store.decode(Blob.fromHex(header+ref(first)+ref(short4095)));
		assertEquals(8192,tree.count());
		assertNotPersisted(tree,store);
	}

	/** Decodes a cell as a peer receives it, so CVM records are specialised */
	static <T extends ACell> T received(ACell value, AStore store) throws Exception {
		return store.decode(value.getEncoding());
	}

	@ParameterizedTest
	@MethodSource("stores")
	public void testBeliefOrdersNotAnIndex(AStore store) throws Exception {
		Belief belief=received(DenseRecord.create(CVMTag.BELIEF,Vectors.of(Vectors.of(1,2,3))),store);
		assertNotPersisted(belief,store);
	}

	@ParameterizedTest
	@MethodSource("stores")
	public void testBlockTransactionsNotAVector(AStore store) throws Exception {
		Block block=received(DenseRecord.create(CVMTag.BLOCK,Vectors.of(CVMLong.ONE,CVMLong.ONE)),store);
		assertNotPersisted(block,store);
	}

	@ParameterizedTest
	@MethodSource("stores")
	public void testOrderBlocksNotAVector(AStore store) throws Exception {
		Order order=received(DenseRecord.create(CVMTag.ORDER,Vectors.of(CVMLong.ONE,Vectors.of(0L),CVMLong.ONE)),store);
		assertNotPersisted(order,store);
	}

	@ParameterizedTest
	@MethodSource("stores")
	public void testSyntaxWrappingSyntax(AStore store) throws Exception {
		Syntax inner=Syntax.create(CVMLong.ONE,Maps.of(Keywords.FOO,CVMLong.ONE));
		Syntax outer=received(Syntax.createRef(Ref.get(inner),Maps.empty()),store);
		assertNotPersisted(outer,store);
	}

	/**
	 * Consensus applies its own rules to the content of Blocks and Orders, so a store
	 * persists any that are structurally correct
	 */
	@ParameterizedTest
	@MethodSource("stores")
	public void testConsensusRulesNotAppliedByStore(AStore store) throws Exception {
		// Execution treats a Block over the transaction limit as invalid
		SignedData<ATransaction> tx=InitTest.HERO_KEYPAIR.signData(Invoke.create(InitTest.HERO,1,CVMLong.ONE));
		Block big=Block.create(1,Vectors.repeat(tx,Constants.MAX_TRANSACTIONS_PER_BLOCK+1));
		assertTrue(Cells.persist(big,store).getRef().getStatus()>=Ref.PERSISTED);

		// Belief merge judges the consensus points an Order claims
		Order order=received(DenseRecord.create(CVMTag.ORDER,Vectors.of(CVMLong.ONE,Vectors.of(5L),Vectors.empty())),store);
		assertTrue(Cells.persist(order,store).getRef().getStatus()>=Ref.PERSISTED);
	}
}
