package convex.etch;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.Blob;
import convex.core.data.Hash;
import convex.core.data.RefSoft;
import convex.core.store.MemoryStore;

/**
 * Index writes cut off by an Error, simulated at a chosen slot write, leave an
 * index that later reads, writes and verification all accept.
 */
public class EtchWriteErrorTest {
	/** Root digit of the chain under test, clear of the root block's ends. */
	private static final int D=0x1234;

	private final InMemoryEtchFileMapper mapper=new InMemoryEtchFileMapper();
	private final Etch etch=new Etch(mapper,"write-error",null,null,null,true);
	private final MemoryStore reader=new MemoryStore();
	private final Map<Hash,Blob> written=new LinkedHashMap<>();
	private int keyCount;

	@BeforeEach
	public void setUp() throws IOException {
		etch.appendNewIndexBlock(0);
	}

	@AfterEach
	public void tearDown() {
		assertAll("Close the file and reader",etch::close,reader::close);
	}

	@Test
	public void testCollapseCutOffWhileClearingKeepsOtherChainsIntact() throws Exception {
		// An unrelated chain a few slots before the one that collapses
		put(key(D-3));
		put(key(D-3));
		// A three-entry chain at D, ended by an entry in the next slot
		put(key(D));
		put(key(D));
		put(key(D));
		put(key(D+3));

		// The fourth entry collapses the chain into a child index, and the
		// clearing of the old continuation slots is cut off at the first slot
		Hash collapsing=key(D);
		mapper.failSlotWrite(v->v==0L,new OutOfMemoryError("Simulated"));
		assertThrows(OutOfMemoryError.class,()->put(collapsing));
		assertEquals(2L,checkIndex().stale);
		assertReadable();

		// A write landing on a stale slot clears the run and takes the slot
		put(key(D+2));
		put(collapsing);
		assertEquals(0L,checkIndex().stale);
		assertReadable();
	}

	@Test
	public void testChainCollapseCutOffWhileClearingAcceptsLaterWrites() throws Exception {
		put(key(D));
		put(key(D));

		// A write landing on the continuation collapses the chain, and the
		// clearing of that continuation is cut off
		Hash landing=key(D+1);
		mapper.failSlotWrite(v->v==0L,new StackOverflowError("Simulated"));
		assertThrows(StackOverflowError.class,()->put(landing));
		assertEquals(1L,checkIndex().stale);
		assertReadable();

		put(landing);
		put(key(D+1));
		assertEquals(0L,checkIndex().stale);
		assertReadable();
	}

	/**
	 * A chain built next to a stale run must not absorb it. Cutting off the
	 * third clear leaves an empty slot before the run, where a new chain's
	 * continuation lands; cutting off the fourth leaves two, where a chain
	 * grows into the second.
	 */
	@ParameterizedTest
	@ValueSource(ints= {3,4})
	public void testChainNextToStaleRunDoesNotAbsorbIt(int failingClear) throws Exception {
		for (int i=0;i<5;i++) put(key(D));
		put(key(D+5));

		Hash collapsing=key(D);
		int[] clears= {0};
		mapper.failSlotWrite(v->(v==0L)&&(++clears[0]==failingClear),
				new OutOfMemoryError("Simulated"));
		assertThrows(OutOfMemoryError.class,()->put(collapsing));
		assertEquals(5L-failingClear,checkIndex().stale);

		put(key(D+1));
		put(key(D+1));
		put(key(D+1));
		put(collapsing);
		assertEquals(0L,checkIndex().stale);
		assertReadable();
	}

	@Test
	public void testChainCreationCutOffLeavesOneEntryChain() throws Exception {
		put(key(D));

		// Cut off between publishing the chain start and its continuation
		Hash second=key(D);
		mapper.failSlotWrite(v->(v&EtchConstants.POINTER_TYPE_MASK)==EtchConstants.POINTER_CHAIN,
				new OutOfMemoryError("Simulated"));
		assertThrows(OutOfMemoryError.class,()->put(second));
		checkIndex();
		assertReadable();
		assertNull(etch.read(second,reader));

		put(second);
		put(key(D));
		checkIndex();
		assertReadable();
	}

	private Hash key(int rootDigit) {
		// Distinct third bytes keep every key in a test apart in level-one indexes
		int n=++keyCount;
		byte[] bytes=new byte[Hash.LENGTH];
		bytes[0]=(byte)(rootDigit>>8);
		bytes[1]=(byte)rootDigit;
		bytes[2]=(byte)n;
		bytes[Hash.LENGTH-1]=(byte)0x5a;
		return Hash.wrap(bytes);
	}

	private void put(Hash key) throws IOException {
		byte[] content=new byte[40];
		System.arraycopy(key.getBytes(),0,content,0,Hash.LENGTH);
		content[content.length-1]=(byte)0xa5;
		Blob value=Blob.wrap(content);
		etch.write(key,value.getRef());
		written.put(key,value);
	}

	private void assertReadable() throws IOException {
		for (Map.Entry<Hash,Blob> entry:written.entrySet()) {
			RefSoft<ACell> ref=etch.read(entry.getKey(),reader);
			assertNotNull(ref,"Missing "+entry.getKey());
			assertArrayEquals(entry.getValue().getEncoding().getBytes(),
					ref.getValue().getEncoding().getBytes());
		}
	}

	private EtchVerifier.IndexVisitor checkIndex() throws IOException {
		EtchVerifier.IndexVisitor visitor=new EtchVerifier.IndexVisitor();
		etch.visitIndex(visitor);
		return visitor;
	}
}
