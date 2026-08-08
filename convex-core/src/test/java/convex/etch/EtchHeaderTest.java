package convex.etch;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.Test;

import convex.core.data.Hash;
import convex.core.util.Utils;

/** Byte-exact tests for canonical legacy Etch files, without filesystem I/O. */
public class EtchHeaderTest {
	@Test
	public void testCanonicalV1File() throws Exception {
		assertCanonicalLegacyFile(EtchConstants.VERSION_1,EtchConstants.V1_INDEX_START);
	}

	@Test
	public void testCanonicalV2File() throws Exception {
		assertCanonicalLegacyFile(EtchConstants.VERSION_2,EtchConstants.V2_INDEX_START);
	}

	private static void assertCanonicalLegacyFile(short version, long expectedIndexStart)
			throws Exception {
		InMemoryEtchFileMapper mapper=new InMemoryEtchFileMapper();
		try (EtchFileAccess access=new EtchFileAccess(mapper,"memory-v"+version,0L,0L)) {
			EtchHeader header=EtchHeader.create(EtchConfig.create(version),null);
			header.initialise(access);

			long expectedLength=expectedIndexStart
					+(long)EtchConstants.ROOT_INDEX_SIZE*EtchConstants.POINTER_SIZE;
			assertEquals(expectedLength,access.getDataLength());
			byte[] file=mapper.copyOf(expectedLength);

			assertEquals(EtchConstants.MAGIC_NUMBER,Utils.readShort(file,0)&0xffff);
			assertEquals(version,Utils.readShort(file,(int)EtchConstants.VERSION_OFFSET));
			assertEquals(expectedLength,Utils.readLong(file,
					(int)EtchConstants.DATA_LENGTH_OFFSET,Long.BYTES));
			assertZero(file,(int)EtchConstants.ROOT_HASH_OFFSET,Math.toIntExact(expectedIndexStart));
			assertZero(file,Math.toIntExact(expectedIndexStart),file.length);

			LegacyEtchHeader decoded=LegacyEtchHeader.decode(file,"memory-v"+version);
			assertEquals(version,decoded.version());
			assertEquals(expectedIndexStart,decoded.indexStart());
			assertEquals(expectedLength,decoded.storedLength());
			assertEquals(Hash.UNSET_HASH,decoded.getRootHash(access));

			byte[] rootBytes=new byte[Hash.LENGTH];
			for (int i=0;i<rootBytes.length;i++) rootBytes[i]=(byte)(i+1);
			Hash root=Hash.wrap(rootBytes);
			decoded.setRootHash(access,root);
			assertEquals(root,decoded.getRootHash(access));
			assertArrayEquals(rootBytes,mapper.copyRange(EtchConstants.ROOT_HASH_OFFSET,
					EtchConstants.ROOT_HASH_OFFSET+Hash.LENGTH));
		}
	}

	private static void assertZero(byte[] data, int start, int end) {
		for (int i=start;i<end;i++) {
			if (data[i]!=0) fail("Expected canonical zero byte at file offset "+i);
		}
	}

}
