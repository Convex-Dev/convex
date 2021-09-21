package etch.api;

import java.io.IOException;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;


import org.junit.jupiter.api.Test;

import convex.core.data.AArrayBlob;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.ABlob;
import convex.core.data.Hash;
import convex.core.data.Ref;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadFormatException;
import convex.core.util.Utils;
import etch.Etch;
import etch.EtchStore;
import etch.IEtchDataEvent;

class EtchDataEvent implements IEtchDataEvent {
	private List<Hash> hashList = new ArrayList<Hash>();

	private byte[] indexBytes = new byte[32];
	private int indexCount = 0;
	private long dataLength = (2 + 8 + 32);			// SIZE_HEADER
	private long maxPosition = 0;

	public void onIndexValue(int value) {
		indexBytes[indexCount] = (byte)value;
		indexCount += 1;
	}
    public void onWalkIndex(long indexPosition) {
		dataLength += (8 * 256);
	}

	public void onData(long keyPosition, AArrayBlob key, long dataPosition, byte flags, long memorySize, ABlob data, short length) {
		hashList.add(Hash.wrap(key));
		dataLength += 32 + (1 + 8 + 4) + length;
		indexCount = 0;
		Arrays.fill(indexBytes, 0, 32, (byte) 0);
		// System.out.println("Add :" + key);
		long endPosition = dataPosition + 1 + 8 + 2 + length;
		if (endPosition > maxPosition) {
			maxPosition = endPosition;
		}
	}
	public List<Hash> getList() {
		return hashList;
	}

	public long getDataLength() {
		return dataLength;
	}
	public long getMaxPosition() {
		return maxPosition;
	}
}

public class TestEtch {
	private static final int ITERATIONS = 3;

	private static final byte[] MAGIC_NUMBER=Utils.hexToBytes("e7c6");

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
	}

	@Test
	public void testRandomWritesStore() throws IOException, BadFormatException {
		EtchStore store=EtchStore.createTemp();
		Etch etch = store.getEtch();

		int COUNT = 1000;
		for (int i = 0; i < COUNT; i++) {
			Long a = (long) i;
			AVector<CVMLong> v=Vectors.of(a);
			Hash key = v.getHash();

			etch.write(key, v.getRef());

			Ref<ACell> r2 = etch.read(key);
			assertEquals(v,r2.getValue());
			assertNotNull(r2, "Stored value not found for vector value: " + v);
		}

		for (int ii = 0; ii < ITERATIONS; ii++) {
			for (int i = 0; i < COUNT; i++) {
				Long a = (long) i;
				AVector<CVMLong> v=Vectors.of(a);
				Hash key = v.getHash();
				Ref<ACell> r2 = etch.read(key);

				assertNotNull(r2, "Stored value not found for vector value: " + v);
				assertEquals(v, r2.getValue());
			}
		}
	}

	@Test
	public void testEtchRepair() throws IOException {
		EtchStore store=EtchStore.createTemp();
		Etch etch = store.getEtch();
		int maxCount = 1000000;
		List<Hash> writeHashList = new ArrayList<Hash>(maxCount);
		for (int index = 0; index < maxCount; index ++) {
			AVector<CVMLong> v=Vectors.of(index);
			Hash h = v.getHash();
			Ref<ACell> r=v.getRef();
			Ref<ACell> r2=etch.write(h, r);
			writeHashList.add(h);
		}
		// now walk through all of the etch hash data
		EtchDataEvent dataEvent = new EtchDataEvent();
		etch.walk(dataEvent);
		List<Hash> hashList = dataEvent.getList();
		assertEquals(hashList.size(), writeHashList.size());
		assertEquals(etch.getDataLength(), dataEvent.getMaxPosition());
	}
}
