package convex.etch;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;

import org.junit.jupiter.api.Test;

import convex.core.data.AString;
import convex.core.data.Hash;
import convex.core.data.Strings;
import convex.core.util.Utils;

/** Tests for explicit read-only maintenance opening, including dirty v3 files. */
public class EtchMaintenanceReaderTest {
	private static final byte[] SECRET=sequence(0x30,32);

	@Test
	public void testLegacyMaintenanceOpenMatrix() throws Exception {
		for (short version:new short[] { EtchConstants.VERSION_1,EtchConstants.VERSION_2 }) {
			File file=tempFile("etch-maintenance-v"+version);
			AString value=Strings.create("legacy maintenance v"+version);
			EtchStore store=new EtchStore(Etch.create(file,EtchConfig.create(version)));
			store.setRootData(value);
			store.flush();
			store.close();

			try (EtchMaintenanceReader reader=EtchMaintenanceReader.openUnsafe(file)) {
				assertEquals(version,reader.getVersion());
				assertEquals(value.getHash(),reader.getRootHash());
				assertEquals(file.length(),reader.getPhysicalFileEnd());
				assertTrue(reader.getLogicalFileEnd()<=reader.getPhysicalFileEnd());
				assertFalse(reader.isCleanClosed());
				assertFalse(reader.isHeaderDegraded());
				assertEquals(-1L,reader.getHeaderGeneration());
				assertNull(reader.getPublicKeyHint());
				byte[] prefix=new byte[4];
				reader.readRaw(0L,prefix,0,prefix.length);
				assertEquals(EtchConstants.MAGIC_NUMBER,Utils.readShort(prefix,0)&0xffff);
				reader.readIndexSlot(reader.getIndexStart()
						+(value.getHash().shortAt(0)&0xffffL)*EtchConstants.POINTER_SIZE);
			}
		}
	}

	@Test
	public void testDirtyV3OpenIsExplicitReadOnlyAndWriterExcluding() throws Exception {
		File file=tempFile("etch-maintenance-dirty");
		AString value=Strings.create("dirty maintenance root");
		EtchStore store=new EtchStore(Etch.create(file,EtchConfig.create(EtchConstants.VERSION_3)));
		store.setRootData(value);
		store.flush();
		store.close();
		byte[] cleanBytes=Files.readAllBytes(file.toPath());
		try (EtchMaintenanceReader clean=EtchMaintenanceReader.openUnsafe(file)) {
			assertTrue(clean.isCleanClosed());
			assertEquals(value.getHash(),clean.getRootHash());
		}
		assertArrayEquals(cleanBytes,Files.readAllBytes(file.toPath()));
		markV3Open(file,null);
		byte[] before=Files.readAllBytes(file.toPath());

		assertThrows(IOException.class,()->Etch.create(file));
		try (EtchMaintenanceReader reader=EtchMaintenanceReader.openUnsafe(file)) {
			assertEquals(EtchConstants.VERSION_3,reader.getVersion());
			assertEquals(value.getHash(),reader.getRootHash());
			assertFalse(reader.isCleanClosed());
			assertFalse(reader.isHeaderDegraded());
			assertTrue(reader.getHeaderGeneration()>=0L);
			IOException locked=assertThrows(IOException.class,()->Etch.create(file));
			assertTrue(locked.getMessage().contains("lock"));
		}
		assertArrayEquals(before,Files.readAllBytes(file.toPath()));
	}

	@Test
	public void testEncryptedDirtyV3ReadsDataAndIndexWithVerifiedKey() throws Exception {
		File file=tempFile("etch-maintenance-encrypted");
		EtchConfig config=EtchConfig.createV3(EtchConfig.MappingMode.MAPPED_BYTE_BUFFER,
				true,EtchConfig.CipherMode.AES_256_CTR,true,null,hint->SECRET.clone());
		AString value=Strings.create("encrypted maintenance root "+"0123456789abcdef".repeat(4));
		EtchStore store=new EtchStore(Etch.create(file,config));
		store.setRootData(value);
		store.flush();
		store.close();
		markV3Open(file,SECRET);

		byte[] wrongSecret=SECRET.clone();
		wrongSecret[0]^=1;
		EtchConfig wrong=EtchConfig.createV3(config.getMappingMode(),true,
				EtchConfig.CipherMode.AES_256_CTR,true,null,hint->wrongSecret.clone());
		assertThrows(IOException.class,()->EtchMaintenanceReader.openUnsafe(file));
		assertThrows(IOException.class,()->EtchMaintenanceReader.openUnsafe(file,wrong));

		try (EtchMaintenanceReader reader=EtchMaintenanceReader.openUnsafe(file,config)) {
			Hash hash=value.getHash();
			assertEquals(hash,reader.getRootHash());
			long slotPosition=reader.getIndexStart()
					+(hash.shortAt(0)&0xffffL)*EtchConstants.POINTER_SIZE;
			long slot=reader.readIndexSlot(slotPosition);
			long recordPosition=slot&~EtchConstants.POINTER_TYPE_MASK;
			byte[] decryptedKey=new byte[Hash.LENGTH];
			reader.readData(recordPosition,decryptedKey,0,decryptedKey.length);
			assertArrayEquals(hash.getBytes(),decryptedKey);
			byte[] rawKey=new byte[Hash.LENGTH];
			reader.readRaw(recordPosition,rawKey,0,rawKey.length);
			assertNotEquals(hash,Hash.wrap(rawKey));
		}
	}

	@Test
	public void testPhysicalTailAndIndependentBounds() throws Exception {
		File file=tempFile("etch-maintenance-tail");
		EtchConfig config=EtchConfig.create(EtchConstants.VERSION_3);
		Etch etch=Etch.create(file,config);
		etch.close();
		markV3Open(file,null);
		long oldPhysical=file.length();
		try (RandomAccessFile data=new RandomAccessFile(file,"rw")) {
			data.seek(oldPhysical);
			data.write(0x5a);
		}

		EtchMaintenanceReader reader=EtchMaintenanceReader.openUnsafe(file);
		long physicalEnd=reader.getPhysicalFileEnd();
		assertEquals(oldPhysical+1L,physicalEnd);
		assertTrue(reader.getLogicalFileEnd()<physicalEnd);
		byte[] tail=new byte[1];
		reader.readRaw(physicalEnd-1L,tail,0,1);
		assertEquals(0x5a,tail[0]&0xff);
		assertThrows(IOException.class,()->reader.readRaw(physicalEnd,tail,0,1));
		assertThrows(IOException.class,()->reader.readRaw(-1L,tail,0,1));
		assertThrows(IOException.class,()->reader.readRaw(Long.MAX_VALUE,tail,0,1));
		assertThrows(IOException.class,()->reader.readData(reader.getBodyStart()-1L,tail,0,1));
		long firstAlignedPastLogical=(reader.getLogicalFileEnd()+7L)&~7L;
		assertThrows(IOException.class,()->reader.readIndexSlot(firstAlignedPastLogical));
		reader.close();
		assertThrows(IOException.class,()->reader.readRaw(0L,tail,0,1));
	}

	@Test
	public void testNormalWriterPreventsMaintenanceOpen() throws Exception {
		File file=tempFile("etch-maintenance-lock");
		Etch etch=Etch.create(file,EtchConfig.create(EtchConstants.VERSION_3));
		try {
			IOException locked=assertThrows(IOException.class,
					()->EtchMaintenanceReader.openUnsafe(file));
			assertTrue(locked.getMessage().contains("lock"));
		} finally {
			etch.close();
		}
	}

	@Test
	public void testExclusiveMaintenanceOpenPreventsAllOtherOpens() throws Exception {
		File file=tempFile("etch-maintenance-exclusive");
		Etch etch=Etch.create(file,EtchConfig.create(EtchConstants.VERSION_3));
		etch.close();

		try (EtchMaintenanceReader reader=EtchMaintenanceReader.openExclusive(file,null)) {
			assertTrue(reader.isCleanClosed());
			assertThrows(IOException.class,()->Etch.create(file));
			assertThrows(IOException.class,()->EtchMaintenanceReader.openUnsafe(file));
		}
	}

	private static void markV3Open(File file, byte[] secret) throws Exception {
		try (RandomAccessFile data=new RandomAccessFile(file,"rw")) {
			EtchV3Header header=(EtchV3Header)EtchHeader.open(data,file.getName(),secret);
			Hash root=header.getRootHash(null);
			byte[] copyA=header.encode(header.generation()+1L,header.syncedFileEnd(),root,
					EtchConstants.V3_OPEN);
			byte[] copyB=header.encode(header.generation()+2L,header.syncedFileEnd(),root,
					EtchConstants.V3_OPEN);
			data.seek(EtchConstants.V3_HEADER_A_OFFSET);
			data.write(copyA);
			data.seek(EtchConstants.V3_HEADER_B_OFFSET);
			data.write(copyB);
			data.getChannel().force(true);
		}
	}

	private static File tempFile(String prefix) throws IOException {
		File file=File.createTempFile(prefix,".etch");
		file.deleteOnExit();
		return file;
	}

	private static byte[] sequence(int start, int length) {
		byte[] result=new byte[length];
		for (int i=0;i<length;i++) result[i]=(byte)(start+i);
		return result;
	}
}
