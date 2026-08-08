package convex.etch;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.List;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Hash;
import convex.core.data.Ref;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.util.Utils;

public class EtchRebuilderTest {
	private static final byte[] SECRET=sequence(0x50,32);

	@Test
	public void testCompleteRebuildMatrix() throws Exception {
		EtchConfig.MappingMode mapped=EtchConfig.MappingMode.MAPPED_BYTE_BUFFER;
		List<EtchConfig> configs=List.of(
				EtchConfig.create(EtchConstants.VERSION_1,mapped,true),
				EtchConfig.create(EtchConstants.VERSION_2,mapped,true),
				EtchConfig.createV3(mapped,true,EtchConfig.CipherMode.NONE,false,null,null),
				EtchConfig.createV3(mapped,true,EtchConfig.CipherMode.AES_256_CTR,
						false,null,hint->SECRET.clone()),
				EtchConfig.createV3(mapped,true,EtchConfig.CipherMode.CHACHA20,
						true,null,hint->SECRET.clone()));

		for (EtchConfig config:configs) {
			File source=tempFile("etch-rebuild-source-v"+config.getVersion());
			File destination=tempFile("etch-rebuild-destination-v"+config.getVersion());
			AString first=largeString("root-first-"+config.getCipherMode().configName());
			AString second=largeString("root-second-"+config.getCipherMode().configName());
			ACell root=Vectors.of(first,second);
			AString extra=largeString("unreachable-extra-"+config.getCipherMode().configName());

			EtchStore sourceStore=new EtchStore(Etch.create(source,config));
			sourceStore.storeTopRef(extra.getRef(),Ref.STORED,null);
			sourceStore.setRootData(root);
			sourceStore.flush();
			sourceStore.close();
			if (config.getVersion()==EtchConstants.VERSION_3) markV3Open(source,config);
			byte[] originalSource=Files.readAllBytes(source.toPath());

			EtchRebuilder.Result result=EtchRebuilder.rebuild(source,config,destination,null);
			assertArrayEquals(originalSource,Files.readAllBytes(source.toPath()),config.toString());
			assertEquals(EtchRebuilder.Status.COMPLETE,result.status(),config.toString());
			assertTrue(result.isRootComplete());
			assertTrue(result.exhaustiveScan());
			assertTrue(result.scannedRecordsAccepted()>0L,config.toString());
			assertTrue(result.destinationValues()>0L);

			EtchStore rebuilt=new EtchStore(Etch.create(destination,config));
			try {
				assertEquals(root,rebuilt.getRootData());
				assertNotNull(rebuilt.getEtch().read(extra.getHash()));
			} finally {
				rebuilt.close();
			}
		}
	}

	@Test
	public void testRecoversUnindexedPhysicalTailRecord() throws Exception {
		EtchConfig config=EtchConfig.createV3(EtchConfig.MappingMode.MAPPED_BYTE_BUFFER,
				true,EtchConfig.CipherMode.NONE,false,null,null);
		File source=tempFile("etch-rebuild-tail-source");
		File destination=tempFile("etch-rebuild-tail-destination");
		Etch etch=Etch.create(source,config);
		etch.close();

		AString tail=largeString("unindexed-tail");
		appendPlainRecord(source,tail);

		EtchRebuilder.Result result=EtchRebuilder.rebuild(source,config,destination,null);
		assertEquals(EtchRebuilder.Status.COMPLETE,result.status());
		assertTrue(result.scannedRecordsAccepted()>0L);
		Etch rebuilt=Etch.create(destination,config);
		EtchStore rebuiltStore=new EtchStore(rebuilt);
		try {
			assertNotNull(rebuilt.read(tail.getHash()));
		} finally {
			rebuiltStore.close();
		}
	}

	@Test
	public void testPartialRebuildLeavesIncompleteRootUnset() throws Exception {
		EtchConfig config=EtchConfig.create(EtchConstants.VERSION_3);
		File source=tempFile("etch-rebuild-partial-source");
		File destination=tempFile("etch-rebuild-partial-destination");
		AString root=largeString("damaged-root");
		AString extra=largeString("recoverable-extra");

		EtchStore store=new EtchStore(Etch.create(source,config));
		store.storeTopRef(extra.getRef(),Ref.STORED,null);
		store.setRootData(root);
		store.flush();
		store.close();

		long rootPosition;
		try (EtchMaintenanceReader reader=EtchMaintenanceReader.openUnsafe(source)) {
			long slotPosition=reader.getIndexStart()
					+(root.getHash().shortAt(0)&0xffffL)*EtchConstants.POINTER_SIZE;
			rootPosition=reader.readIndexSlot(slotPosition)&~EtchConstants.POINTER_TYPE_MASK;
		}
		try (RandomAccessFile data=new RandomAccessFile(source,"rw")) {
			long encodingPosition=rootPosition+EtchConstants.KEY_SIZE
					+EtchConstants.LABEL_SIZE+EtchConstants.ENCODING_LENGTH_SIZE;
			data.seek(encodingPosition);
			int value=data.read();
			data.seek(encodingPosition);
			data.write(value^1);
			data.getChannel().force(true);
		}

		EtchRebuilder.Result result=EtchRebuilder.rebuild(source,config,destination,null);
		assertEquals(EtchRebuilder.Status.PARTIAL,result.status());
		assertFalse(result.isRootComplete());
		assertTrue(result.missingRootHashes().contains(root.getHash()));

		Etch rebuilt=Etch.create(destination,config);
		EtchStore rebuiltStore=new EtchStore(rebuilt);
		try {
			assertEquals(Hash.UNSET_HASH,rebuilt.getRootHash());
			assertNotNull(rebuilt.read(extra.getHash()));
		} finally {
			rebuiltStore.close();
		}
	}

	private static void appendPlainRecord(File file, ACell cell) throws IOException {
		Blob encoding=cell.getEncoding();
		byte[] record=new byte[EtchConstants.KEY_SIZE+EtchConstants.LABEL_SIZE
				+EtchConstants.ENCODING_LENGTH_SIZE+Math.toIntExact(encoding.count())];
		cell.getHash().getBytes(record,0);
		record[EtchConstants.KEY_SIZE]=(byte)Ref.STORED;
		Utils.writeShort(record,EtchConstants.KEY_SIZE+EtchConstants.LABEL_SIZE,
				(short)encoding.count());
		encoding.getBytes(record,EtchConstants.KEY_SIZE+EtchConstants.LABEL_SIZE
				+EtchConstants.ENCODING_LENGTH_SIZE);
		try (RandomAccessFile data=new RandomAccessFile(file,"rw")) {
			data.seek(data.length());
			data.write(record);
			data.getChannel().force(true);
		}
	}

	private static void markV3Open(File file, EtchConfig config) throws Exception {
		byte[] secret=(config.getCipherMode()==EtchConfig.CipherMode.NONE)?null
				:config.resolveKey(config.getPublicKeyHint());
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
		} finally {
			if (secret!=null) java.util.Arrays.fill(secret,(byte)0);
		}
	}

	private static AString largeString(String prefix) {
		return Strings.create((prefix+"-0123456789abcdef").repeat(12));
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
