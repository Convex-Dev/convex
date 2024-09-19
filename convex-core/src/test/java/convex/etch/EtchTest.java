package convex.etch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Random;

import org.junit.jupiter.api.Test;

import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Hash;
import convex.core.data.Ref;
import convex.core.data.Refs;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadFormatException;
import convex.core.util.Utils;
import convex.test.Samples;

public class EtchTest {

	@Test
	public void testTempStore() throws IOException {
		EtchStore store=EtchStore.createTemp();
		Etch etch = store.getEtch();

		AVector<CVMLong> v=Vectors.of(1,2,3);
		Hash h = v.getHash();
		Ref<ACell> r=v.getRef();

		assertNull(etch.read(h));

		// write the Ref
		Ref<ACell> r2=etch.write(h, r);

		assertEquals(v.getEncoding(), etch.read(h).getValue().getEncoding());

		assertEquals(h,r2.getHash());
		
		store.setRootData(null);
		assertNull(store.getRootData());
	}
	
	@Test 
	public void testChainClash() throws IOException {
		Etch etch = EtchStore.createTemp().getEtch();
		// We fake writes with similar hashes, will cause chains.
		Hash k1=Hash.fromHex("0000000000000001000000000000000000000000000000000000000000000000");
		Hash k2=Hash.fromHex("0000000000000002000000000000000000000000000000000000000000000000");
		Hash k3=Hash.fromHex("0000000000000003000000000000000000000000000000000000000000000000");
		Hash kb=Hash.fromHex("0002000000000000000000000000000000000000000000000000000000000000");
		
		CVMLong v1=CVMLong.create(1);
		CVMLong v2=CVMLong.create(2);
		CVMLong v3=CVMLong.create(3);
		CVMLong vb=CVMLong.create(4);
	
		// This value blocks a chain assuming there is nothing else in db
		etch.write(kb, vb.getRef());
		
		etch.write(k1, v1.getRef());
		etch.write(k2, v2.getRef());
		etch.write(k3, v3.getRef());
		
		assertEquals(v1,etch.read(k1).getValue());
		assertEquals(v2,etch.read(k2).getValue());
		assertEquals(v3,etch.read(k3).getValue());
		assertEquals(vb,etch.read(kb).getValue());
	}
	
	@Test 
	public void testChainFill() throws IOException {
		int FILL=512;
		
		Etch etch = EtchStore.createTemp().getEtch();
		// Blocking values all ones
		Hash[] b=new Hash[9];
		b[0]=Hash.fromHex("0000000000000000000000000000000000000000000000000000000000000000");
		b[1]=Hash.fromHex("1000000000000000000000000000000000000000000000000000000000000000");
		b[2]=Hash.fromHex("0100000000000000000000000000000000000000000000000000000000000000");
		b[3]=Hash.fromHex("0010000000000000000000000000000000000000000000000000000000000000");
		b[4]=Hash.fromHex("0001000000000000000000000000000000000000000000000000000000000000");
		b[5]=Hash.fromHex("0000100000000000000000000000000000000000000000000000000000000000");
		b[6]=Hash.fromHex("0000010000000000000000000000000000000000000000000000000000000000");
		b[7]=Hash.fromHex("0000001000000000000000000000000000000000000000000000000000000000");
		b[8]=Hash.fromHex("0000000100000000000000000000000000000000000000000000000000000000");
		for (int i=0; i<=8; i++) {
			etch.write(b[i],b[i].getRef());
		}	
		for (int i=0; i<=8; i++) {
			assertEquals(b[i],etch.read(b[i]).getValue());
		}
		
		Hash[] f= new Hash[FILL+1];
		for (int i=0; i<=FILL; i++) {
			String sk=Blob.create(new byte[] {(byte) (i>>8),(byte) i}).toHexString();
			String hs="000000"+sk+"0000000000000000000000000000000000000000000000000000ff";
			Hash h=Hash.fromHex(hs);
			f[i]=h;
		}
		
		assertNull(etch.read(f[0])); // first item isn't written yet
		
		// write all the f values except the last one in random sequence
		ArrayList<Integer> al=new ArrayList<>(FILL);
		for (int i=0; i<FILL; i++) al.add(i);
		Utils.shuffle(al, new Random(5657));
		for (int i=0; i<FILL; i++) {
			int ix=al.get(i);
			etch.write(f[ix], f[ix].getRef());
		}
		
		for (int i=0; i<FILL; i++) {
			assertEquals(f[i],etch.read(f[i]).getValue());
		}

		assertNull(etch.read(f[FILL])); // last item still not written
		
		EtchUtils.FullValidator vd=EtchUtils.getFullValidator();
		etch.visitIndex(vd);
		
		assertEquals(40,vd.visited);
		assertEquals(b.length+FILL,vd.values);
	}

	@Test
	public void testRandomWritesStore() throws IOException, BadFormatException {
		EtchStore store=EtchStore.createTemp();
		Etch etch = store.getEtch();

		int COUNT = 4000; // enough to cause some collisions in top level index at least
		for (int i = 0; i < COUNT; i++) {
			Long a = (long) i;
			AVector<CVMLong> v=Vectors.of(a);
			Hash key = v.getHash();

			etch.write(key, v.getRef());

			Ref<ACell> r2 = etch.read(key);
			assertEquals(v,r2.getValue());
			assertNotNull(r2, "Stored value not found for vector value: " + v);
		}

		
		HashSet<ABlob> hs=new HashSet<ABlob>();
		etch.visitIndex(new IEtchIndexVisitor() {
			@Override
			public void visit(Etch e, int level, int[] digits, long indexPointer)  {
				try {
					assertSame(etch,e);
					int n=e.indexSize(level);
					for (int i=0; i<n; i++) {
						long slot=e.readSlot(indexPointer,i);
						long type=slot&Etch.TYPE_MASK;
						if ((type==Etch.PTR_PLAIN)||(type==Etch.PTR_START)||(type==Etch.PTR_CHAIN)) {
							if (slot!=0) {
								long pointer=e.rawPointer(slot);
								Blob k=e.readBlob(pointer, 32);
								hs.add(k);
							}
						}
					}
				} catch (IOException ex) {
					throw Utils.sneakyThrow(ex);
				}
			}
		});
		
		assertEquals(COUNT,hs.size());
		
		for (int i = 0; i < COUNT; i++) {
			Long a = (long) i;
			AVector<CVMLong> v=Vectors.of(a);
			Hash key = v.getHash();
			assertTrue(hs.contains(key.toFlatBlob()));
			Ref<ACell> r2 = etch.read(key);

			assertNotNull(r2, "Stored value not found for vector value: " + v);
			assertEquals(v, r2.getValue());
		}

		EtchUtils.FullValidator vd=EtchUtils.getFullValidator();
		etch.visitIndex(vd);
		assertEquals(COUNT,vd.values);
		assertTrue(vd.visited>1);
	}

	@Test
	public void testLargeStore() throws IOException {
		EtchStore store=EtchStore.createTemp();
		Etch etch = store.getEtch();

		// this gets the data saved over 1GB
		// int COUNT = 6120700;
		int COUNT=10000;
		Random random = new Random();
		for (int i = 0; i < COUNT; i++) {
			doStoreWrite(etch, random);
		}
	}

	private void doStoreWrite(Etch etch, Random random) throws IOException {
		AVector<CVMLong> v=Vectors.of(random.nextLong());
		Hash key = v.getHash();
		Ref<ACell> r=v.getRef();

		assertNull(etch.read(key));
		// write the Ref
		Ref<ACell> r2=etch.write(key, r);
		assertEquals(key,r2.getHash());
		assertTrue(etch.getDataLength() > 0);
		// System.out.println(i + " " +  COUNT);
	}
	
	@Test 
	public void testCopyAcrossStores() throws IOException {
		EtchStore store=EtchStore.createTemp();
		EtchStore store2=EtchStore.createTemp();
		AString nestedString=Samples.NON_EMBEDDED_STRING;
		ABlob nestedBlob=Samples.NON_EMBEDDED_BLOB;
		ACell v=Vectors.of(1,nestedString,Vectors.of(2,nestedBlob));
		assertTrue(v.isEmbedded());
		assertFalse(v.getRef(1).isEmbedded());
		
		Hash h=v.getHash();
		assertNull(store.refForHash(h));
		
		Ref<ACell> r=store.storeRef(v.getRef(), Ref.PERSISTED, null,true); // note top level
		assertTrue(r.isPersisted());
		assertEquals(h,r.getHash()); // TODO: should be identical?
		
		Refs.checkConsistentStores(r, store);
		
		// should now be persisted in first store, but not second
		assertNotNull(store.refForHash(h));
		assertNull(store2.refForHash(h));
		
		Ref<ACell> r2=store2.storeRef(v.getRef(), Ref.PERSISTED, null,true); // note top level
		assertNotNull(store2.refForHash(h));
		
		Refs.checkConsistentStores(r, store); // should be unchanged
		Refs.checkConsistentStores(r2, store2);
		
		ACell v2=r2.getValue();
		assertEquals(v,v2);
		assertNotSame(v,v2);
	}
}
