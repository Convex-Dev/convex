package convex.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
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
import convex.core.data.AHashMap;
import convex.core.data.ASet;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Cells;
import convex.core.data.DenseRecord;
import convex.core.data.Index;
import convex.core.data.Maps;
import convex.core.data.Ref;
import convex.core.data.Sets;
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
 * each of its children (<a href="https://docs.convex.world/docs/cad/stores">CAD048</a>).
 * These tests decode cells that are each correctly
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
		AVector<?> tree=store.decode(Blob.fromHex("80"+badTreeBody()));
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

	/** Body of a vector tree claiming 256 elements, whose last embedded leaf holds 15 */
	public static String badTreeBody() {
		StringBuilder sb=new StringBuilder("8200");
		for (int i=0; i<15; i++) sb.append(smallLeaf(16));
		return sb.append(smallLeaf(15)).toString();
	}

	@ParameterizedTest
	@MethodSource("stores")
	public void testVectorLeafPrefixCount(AStore store) throws Exception {
		// 17 elements claimed: one element after an embedded prefix holding 15
		AVector<?> v=store.decode(Blob.fromHex("8011"+"1101"+smallLeaf(15)));
		assertEquals(17,v.count());
		assertNotPersisted(v,store);
	}

	@ParameterizedTest
	@MethodSource("stores")
	public void testVectorLeafPrefixNotAVector(AStore store) throws Exception {
		AVector<?> v=store.decode(Blob.fromHex("8011"+"1101"+"1101"));
		assertNotPersisted(v,store);
	}

	/** Cells that encode as the vector or blob tree they wrap take its children */
	@ParameterizedTest
	@MethodSource("stores")
	public void testWrappersOfInvalidTrees(AStore store) throws Exception {
		String body=badTreeBody();
		// List, a dense record that CVM cannot specialise, a multi-op and a multi-function
		for (String tag: new String[] {"81","d0","da","df"}) {
			ACell wrapper=store.decode(Blob.fromHex(tag+body));
			String what=tag+" decoded as "+wrapper.getClass().getSimpleName();
			assertThrows(InvalidStructureException.class, ()->Cells.persist(wrapper, store), what);
		}

		// String over a blob tree whose second chunk is short
		byte[] bytes=new byte[8192];
		ABlob valid=Blob.wrap(bytes).toCanonical();
		Blob encoding=valid.getEncoding();
		String header=encoding.slice(1,encoding.count()-2*33).toHexString();
		ACell first=Cells.persist(Blob.wrap(bytes,0,4096),store);
		ACell short4095=Cells.persist(Blob.wrap(bytes,4096,4095),store);
		ACell string=store.decode(Blob.fromHex("30"+header+ref(first)+ref(short4095)));
		assertNotPersisted(string,store);
	}

	static Blob patch(Blob encoding, int offset, int value) {
		byte[] bs=encoding.getBytes();
		bs[offset]=(byte)value;
		return Blob.wrap(bs);
	}

	/** Moves the lowest bit of the 16-bit child mask at the offset to the lowest unused digit */
	static Blob moveMaskBit(Blob encoding, int offset) {
		byte[] bs=encoding.getBytes();
		int mask=((bs[offset]&0xFF)<<8)|(bs[offset+1]&0xFF);
		mask=(mask&~Integer.lowestOneBit(mask))|Integer.lowestOneBit(~mask&0xFFFF);
		bs[offset]=(byte)(mask>>8);
		bs[offset+1]=(byte)mask;
		return Blob.wrap(bs);
	}

	/** Replaces the last child reference of a tree whose encoding ends with its children */
	static Blob replaceLastChild(Blob encoding, ACell child) {
		String hex=encoding.toHexString();
		return Blob.fromHex(hex.substring(0,hex.length()-64)+child.getHash().toHexString());
	}

	/** Blob of n bytes, which is embedded for n up to 138 */
	static Blob bytes(int seed, int n) {
		byte[] bs=new byte[n];
		for (int i=0; i<n; i++) bs[i]=(byte)(seed*31+i);
		return Blob.wrap(bs);
	}

	/**
	 * Hashed trees (maps and sets) and the Index: count, digit and child type, with
	 * small children (embedded) and large ones (branches).
	 *
	 * @param tree Valid tree, persisted first so its branch children are in the store
	 * @param maskOffset Offset of the child mask in the tree's encoding
	 */
	static void checkKeyedTree(ACell tree, int maskOffset, AStore store) throws Exception {
		tree=Cells.persist(tree,store);
		Blob encoding=tree.getEncoding();
		// Count one more than the children hold (a single VLQ byte at offset 1)
		assertNotPersisted(store.decode(patch(encoding,1,encoding.byteAtUnchecked(1)+1)),store);
		// A child under the wrong digit
		assertNotPersisted(store.decode(moveMaskBit(encoding,maskOffset)),store);
	}

	static void checkKeyedTreeBranches(ACell tree, AStore store) throws Exception {
		tree=Cells.persist(tree,store);
		Blob encoding=tree.getEncoding();
		ACell firstChild=tree.getRef(0).getValue();
		ACell vector=Cells.persist(Vectors.repeat(bytes(0,100),3),store);
		// The first child again, in the last child's place
		assertNotPersisted(store.decode(replaceLastChild(encoding,firstChild)),store);
		// A child of the wrong type
		assertNotPersisted(store.decode(replaceLastChild(encoding,vector)),store);
	}

	@ParameterizedTest
	@MethodSource("stores")
	public void testMapTree(AStore store) throws Exception {
		AHashMap<ACell,ACell> small=Maps.empty();
		AHashMap<ACell,ACell> large=Maps.empty();
		for (int i=1; i<=20; i++) {
			small=small.assoc(CVMLong.create(i),CVMLong.create(i));
			large=large.assoc(CVMLong.create(i),bytes(i,135));
		}
		checkKeyedTree(small,3,store);
		checkKeyedTreeBranches(large,store);
	}

	@ParameterizedTest
	@MethodSource("stores")
	public void testSetTree(AStore store) throws Exception {
		ASet<ACell> small=Sets.empty();
		ASet<ACell> large=Sets.empty();
		for (int i=1; i<=20; i++) {
			small=small.conj(CVMLong.create(i));
			large=large.conj(bytes(i,137));
		}
		checkKeyedTree(small,3,store);
		checkKeyedTreeBranches(large,store);
	}

	@ParameterizedTest
	@MethodSource("stores")
	public void testIndexTree(AStore store) throws Exception {
		Index<ABlob,ACell> small=Index.none();
		Index<ABlob,ACell> large=Index.none();
		for (int i=1; i<=20; i++) {
			Blob key=Blob.fromHex(String.format("%02x",i));
			small=small.assoc(key,CVMLong.create(i));
			large=large.assoc(key,bytes(i,135));
		}
		// Encoding: tag, count, no entry, depth, mask
		checkKeyedTree(small,4,store);
		// A depth the children do not exceed
		Index<ABlob,ACell> persisted=Cells.persist(small,store);
		assertNotPersisted(store.decode(patch(persisted.getEncoding(),3,1)),store);
		checkKeyedTreeBranches(large,store);
	}

	/** A child as its parent encodes it: inline if embedded, otherwise persisted and referenced */
	static String child(ACell c, AStore store) throws Exception {
		if (c.isEmbedded()) return c.getEncoding().toHexString();
		return ref(Cells.persist(c,store));
	}

	/** Hex encoding of a map or set tree at shift 0, from its children by digit */
	static String hashTreeHex(String tag, int count, TreeMap<Integer,String> children) {
		return hashTreeHex(tag,count,0,children);
	}

	/** Hex encoding of a map or set tree, from its children by digit (count under 128) */
	static String hashTreeHex(String tag, int count, int shift, TreeMap<Integer,String> children) {
		int mask=0;
		StringBuilder sb=new StringBuilder();
		for (Map.Entry<Integer,String> e: children.entrySet()) {
			mask|=1<<e.getKey();
			sb.append(e.getValue());
		}
		return tag+String.format("%02x",count)+String.format("%02x",shift)+String.format("%04x",mask)+sb;
	}

	/** First hex digit of a value's hash, which decides its child in a tree at shift 0 */
	static int digit(ACell value) {
		return digit(value,0);
	}

	static int digit(ACell value, int position) {
		return value.getHash().getHexDigit(position);
	}

	/**
	 * A tree at shift 1 holds keys sharing one first digit. A key with another first
	 * digit, placed under the right second digit, is caught only by the shared prefix.
	 */
	@ParameterizedTest
	@MethodSource("stores")
	public void testHashTreeSharedPrefix(AStore store) throws Exception {
		// Keys whose hash starts with the same digit as that of key 1
		int first=digit(CVMLong.ONE);
		java.util.List<CVMLong> keys=new java.util.ArrayList<>();
		for (long i=1; keys.size()<20; i++) {
			CVMLong k=CVMLong.create(i);
			if (digit(k)==first) keys.add(k);
		}
		// A key with another first digit and the second digit of keys.get(0)
		int second=digit(keys.get(0),1);
		CVMLong stray=CVMLong.create(100000);
		while ((digit(stray)==first)||(digit(stray,1)!=second)) stray=CVMLong.create(stray.longValue()+1);

		TreeMap<Integer,AHashMap<ACell,ACell>> mapLeaves=new TreeMap<>();
		TreeMap<Integer,ASet<ACell>> setLeaves=new TreeMap<>();
		for (CVMLong k: keys) {
			mapLeaves.merge(digit(k,1),Maps.of(k,k),(a,b)->a.merge(b));
			setLeaves.merge(digit(k,1),Sets.of(k),(a,b)->a.includeAll(b));
		}
		for (String tag: new String[] {"82","83"}) {
			boolean map=tag.equals("82");
			TreeMap<Integer,String> valid=new TreeMap<>();
			TreeMap<Integer,String> invalid=new TreeMap<>();
			for (int d: mapLeaves.keySet()) {
				ACell leaf=map?mapLeaves.get(d):setLeaves.get(d);
				valid.put(d,child(leaf,store));
				if (d==second) leaf=map?mapLeaves.get(d).assoc(stray,stray):setLeaves.get(d).conj(stray);
				invalid.put(d,child(leaf,store));
			}
			assertTrue(Cells.persist(store.decode(Blob.fromHex(hashTreeHex(tag,20,1,valid))),store).getRef().getStatus()>=Ref.PERSISTED);
			assertNotPersisted(store.decode(Blob.fromHex(hashTreeHex(tag,21,1,invalid))),store);
		}
	}

	@ParameterizedTest
	@MethodSource("stores")
	public void testHashTreeChildren(AStore store) throws Exception {
		// Leaves of keys grouped by first digit, as a valid tree at shift 0 holds them
		TreeMap<Integer,AHashMap<ACell,ACell>> mapLeaves=new TreeMap<>();
		TreeMap<Integer,ASet<ACell>> setLeaves=new TreeMap<>();
		AHashMap<ACell,ACell> allMap=Maps.empty();
		ASet<ACell> allSet=Sets.empty();
		for (int i=1; i<=20; i++) {
			CVMLong k=CVMLong.create(i);
			mapLeaves.merge(digit(k),Maps.of(k,k),(a,b)->a.merge(b));
			setLeaves.merge(digit(k),Sets.of(k),(a,b)->a.includeAll(b));
			allMap=allMap.assoc(k,k);
			allSet=allSet.conj(k);
		}
		// The lowest first digit, and a key whose first digit is higher
		int low=mapLeaves.firstKey();
		CVMLong high=CVMLong.create(100);
		while (digit(high)<=low) high=CVMLong.create(high.longValue()+1);

		for (String tag: new String[] {"82","83"}) {
			boolean map=tag.equals("82");
			TreeMap<Integer,String> children=new TreeMap<>();
			for (int d: mapLeaves.keySet()) children.put(d,child(map?mapLeaves.get(d):setLeaves.get(d),store));
			assertTrue(Cells.persist(store.decode(Blob.fromHex(hashTreeHex(tag,20,children))),store).getRef().getStatus()>=Ref.PERSISTED);

			// An empty child at an unused digit
			int unused=0;
			while (children.containsKey(unused)) unused++;
			TreeMap<Integer,String> withEmpty=new TreeMap<>(children);
			withEmpty.put(unused,tag+"00");
			assertNotPersisted(store.decode(Blob.fromHex(hashTreeHex(tag,20,withEmpty))),store);

			// A tree at shift 0 as a child of a tree at shift 0, with keys of every digit
			TreeMap<Integer,String> nested=new TreeMap<>();
			nested.put(low,child(map?allMap:allSet,store));
			nested.put(digit(high),child(map?Maps.of(high,high):Sets.of(high),store));
			assertNotPersisted(store.decode(Blob.fromHex(hashTreeHex(tag,21,nested))),store);
		}
	}

	@ParameterizedTest
	@MethodSource("stores")
	public void testIndexEntries(AStore store) throws Exception {
		// Count 2: an entry, then a child holding key 0x1234 under digit 2
		String child="8401"+"31021234"+"1101";
		// Entry key that is not a blob
		assertNotPersisted(store.decode(Blob.fromHex("8402"+"80"+"1101"+"1101"+"00"+"0002"+"8401"+"310112"+"1101")),store);
		// Entry key 0x12 at depth 1, where its two hex digits put it at depth 2
		assertNotPersisted(store.decode(Blob.fromHex("8402"+"80"+"310112"+"1101"+"01"+"0004"+child)),store);
	}

	@ParameterizedTest
	@MethodSource("stores")
	public void testIndexChildPrefix(AStore store) throws Exception {
		Index<ABlob,ACell> index=Index.none();
		for (int i=1; i<=20; i++) index=index.assoc(Blob.fromHex(String.format("%02x",i)),bytes(i,135));
		index=Cells.persist(index,store);
		// The node for keys 0x01-0x0f, at depth 1, with its last child (key 0x0f) replaced
		// by a node for key 0x1f: the right digit at depth 1, the wrong prefix
		ACell node=index.getRef(0).getValue();
		Index<ABlob,ACell> other=Cells.persist(Index.of(Blob.fromHex("1f"),bytes(99,135)),store);
		assertNotPersisted(store.decode(replaceLastChild(node.getEncoding(),other)),store);
	}

	@ParameterizedTest
	@MethodSource("stores")
	public void testBlobTreeMiddleChild(AStore store) throws Exception {
		// 12288 bytes claimed in three chunks of 4095, 4097 and 4096 bytes
		byte[] bytes=new byte[12288];
		for (int i=0; i<bytes.length; i++) bytes[i]=(byte)i;
		Blob encoding=Blob.wrap(bytes).toCanonical().getEncoding();
		String header=encoding.slice(0,encoding.count()-3*33).toHexString();
		String children=ref(Cells.persist(Blob.wrap(bytes,0,4095),store))
			+ref(Cells.persist(Blob.wrap(bytes,4095,4097).toCanonical(),store))
			+ref(Cells.persist(Blob.wrap(bytes,8192,4096),store));
		ABlob tree=store.decode(Blob.fromHex(header+children));
		assertEquals(12288,tree.count());
		assertNotPersisted(tree,store);
	}

	/** Checking a tree that refers to the same cells many times visits each once */
	@Test
	public void testCheckTreeVisitsSharedCellsOnce() {
		ACell v=Vectors.of(1);
		for (int i=0; i<64; i++) v=Vectors.of(v,v);
		ACell shared=v;
		// Visiting each of the 2^64 paths instead would never finish
		assertTimeoutPreemptively(Duration.ofSeconds(10),() -> Cells.checkTree(shared));
	}

	/**
	 * Checking a received value stops at a branch its store holds as persisted, which
	 * has been checked, rather than walking the stored tree again
	 */
	@Test
	public void testCheckTreeStopsAtPersistedBranch() throws Exception {
		try (EtchStore store=EtchStore.createTemp("structure-check")) {
			// An invalid tree recorded as persisted directly, as a store written before
			// these checks may hold one
			ACell full=Cells.persist(store.decode(Blob.fromHex(bigLeaf(16))),store);
			ACell short15=Cells.persist(store.decode(Blob.fromHex(bigLeaf(15))),store);
			ACell bad=store.decode(Blob.fromHex("8020"+ref(full)+ref(short15)));
			store.getEtch().write(bad.getHash(),bad.getRef().withStatus(Ref.PERSISTED));

			ACell received=store.decode(Blob.fromHex("8001"+ref(bad)));
			Cells.checkTree(received);
			assertThrows(InvalidStructureException.class,()->Cells.checkTree(bad));
		}
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
