package convex.etch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.List;

import org.junit.jupiter.api.Test;

import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Hash;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.crypto.Hashing;
import convex.core.store.MemoryStore;

public class EtchStrictValidatorTest {
	private static final byte[] SECRET=sequence(0x20,32);

	@Test
	public void testValidFormatAndCipherMatrix() throws Exception {
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
			File file=tempFile("etch-strict-v"+config.getVersion());
			AString child=largeString("child-"+config.getCipherMode().configName());
			EtchStore store=new EtchStore(Etch.create(file,config));
			store.setRootData(Vectors.of(child,largeString("second")));
			store.flush();
			store.close();

			EtchStrictValidator.Report report=EtchStrictValidator.validate(file,config);
			assertTrue(report.isValid(),config+" "+report);
			assertEquals(0L,report.failureCount(),config.toString());
			assertTrue(report.records()>=2L,config.toString());
		}
	}

	@Test
	public void testHashCorruptionIsReportedWithoutThrowing() throws Exception {
		EtchConfig.MappingMode mapped=EtchConfig.MappingMode.MAPPED_BYTE_BUFFER;
		List<EtchConfig> configs=List.of(
				EtchConfig.createV3(mapped,true,EtchConfig.CipherMode.NONE,false,null,null),
				EtchConfig.createV3(mapped,true,EtchConfig.CipherMode.AES_256_CTR,
						false,null,hint->SECRET.clone()),
				EtchConfig.createV3(mapped,true,EtchConfig.CipherMode.CHACHA20,
						true,null,hint->SECRET.clone()));
		for (EtchConfig config:configs) {
			File file=tempFile("etch-strict-hash-"+config.getCipherMode().configName());
			AString root=largeString("damaged-root-"+config.getCipherMode().configName());
			EtchStore store=new EtchStore(Etch.create(file,config));
			store.setRootData(root);
			store.flush();
			store.close();

			long recordPosition=findRootRecord(file,config,root.getHash());
			try (RandomAccessFile data=new RandomAccessFile(file,"rw")) {
				long encodingPosition=recordPosition+EtchRecordVerifier.RECORD_HEADER_SIZE;
				data.seek(encodingPosition);
				int original=data.readUnsignedByte();
				data.seek(encodingPosition);
				data.writeByte(original^1);
			}

			EtchStrictValidator.Report report=EtchStrictValidator.validate(file,config,
					new EtchStrictValidator.Options(1));
			assertFalse(report.isValid(),config.toString());
			assertEquals(1L,report.hashMismatches(),config.toString());
			assertEquals(1L,report.missingRootHashes(),config.toString());
			assertEquals(1,report.problems().size(),config.toString());
		}
	}

	@Test
	public void testInvalidRecordLengthIsRejectedBeforeAllocation() throws Exception {
		EtchConfig config=EtchConfig.create(EtchConstants.VERSION_3);
		File file=tempFile("etch-strict-length");
		AString root=largeString("invalid-length");
		EtchStore store=new EtchStore(Etch.create(file,config));
		store.setRootData(root);
		store.flush();
		store.close();

		long recordPosition=findRootRecord(file,config,root.getHash());
		try (RandomAccessFile data=new RandomAccessFile(file,"rw")) {
			data.seek(recordPosition+EtchConstants.KEY_SIZE+EtchConstants.LABEL_SIZE);
			data.writeShort(0xffff);
		}

		EtchStrictValidator.Report report=EtchStrictValidator.validate(file,config);
		assertFalse(report.isValid());
		assertEquals(1L,report.malformedEntries());
		assertEquals(0L,report.hashMismatches());
	}

	@Test
	public void testStoredHashCorruptionIsReported() throws Exception {
		EtchConfig config=EtchConfig.create(EtchConstants.VERSION_3);
		File file=tempFile("etch-strict-stored-hash");
		AString root=largeString("stored-hash");
		EtchStore store=new EtchStore(Etch.create(file,config));
		store.setRootData(root);
		store.flush();
		store.close();

		long recordPosition=findRootRecord(file,config,root.getHash());
		try (RandomAccessFile data=new RandomAccessFile(file,"rw")) {
			data.seek(recordPosition);
			int value=data.readUnsignedByte();
			data.seek(recordPosition);
			data.writeByte(value^1);
		}

		EtchStrictValidator.Report report=EtchStrictValidator.validate(file,config);
		assertFalse(report.isValid());
		assertEquals(1L,report.hashMismatches());
	}

	@Test
	public void testInvalidIndexPointerIsReportedWithoutFollowingIt() throws Exception {
		EtchConfig config=EtchConfig.create(EtchConstants.VERSION_3);
		File file=tempFile("etch-strict-pointer");
		AString root=largeString("invalid-pointer");
		EtchStore store=new EtchStore(Etch.create(file,config));
		store.setRootData(root);
		store.flush();
		store.close();

		long slotPosition;
		long fileEnd;
		try (EtchMaintenanceReader reader=EtchMaintenanceReader.openUnsafe(file,config)) {
			slotPosition=reader.getIndexStart()
					+(root.getHash().shortAt(0)&0xffffL)*EtchConstants.POINTER_SIZE;
			fileEnd=reader.getLogicalFileEnd();
		}
		try (RandomAccessFile data=new RandomAccessFile(file,"rw")) {
			data.seek(slotPosition);
			data.writeLong(fileEnd+1024L);
		}

		EtchStrictValidator.Report report=EtchStrictValidator.validate(file,config);
		assertFalse(report.isValid());
		assertTrue(report.malformedEntries()>0L,report.toString());
		assertEquals(1L,report.missingRootHashes());
	}

	@Test
	public void testTruncatedRecordExtentIsReported() throws Exception {
		EtchConfig config=EtchConfig.create(EtchConstants.VERSION_3);
		File file=tempFile("etch-strict-truncated");
		AString root=largeString("truncated-record");
		EtchStore store=new EtchStore(Etch.create(file,config));
		store.setRootData(root);
		store.flush();
		store.close();

		long slotPosition;
		long fileEnd;
		try (EtchMaintenanceReader reader=EtchMaintenanceReader.openUnsafe(file,config)) {
			slotPosition=reader.getIndexStart()
					+(root.getHash().shortAt(0)&0xffffL)*EtchConstants.POINTER_SIZE;
			fileEnd=reader.getLogicalFileEnd();
		}
		try (RandomAccessFile data=new RandomAccessFile(file,"rw")) {
			data.seek(slotPosition);
			data.writeLong(fileEnd-1L);
		}

		EtchStrictValidator.Report report=EtchStrictValidator.validate(file,config);
		assertFalse(report.isValid());
		assertTrue(report.problems().stream().anyMatch(p ->
				p.message().contains("record header is outside")),report.toString());
	}

	@Test
	public void testNonCanonicalAndTrailingCAD3AreRejected() {
		MemoryStore decoder=new MemoryStore();
		try {
			EtchRecordVerifier verifier=new EtchRecordVerifier(null,decoder);
			Blob nonCanonicalCount=Blob.fromHex("808000");
			EtchRecordVerifier.Result nonCanonical=verifier.verify(1L,
					Hashing.sha3(nonCanonicalCount),nonCanonicalCount);
			assertFalse(nonCanonical.isValid());
			assertEquals(EtchRecordVerifier.FailureKind.ENCODING,
					nonCanonical.failure().kind());

			Blob trailing=Blob.fromHex("1000");
			EtchRecordVerifier.Result excess=verifier.verify(2L,
					Hashing.sha3(trailing),trailing);
			assertFalse(excess.isValid());
			assertEquals(EtchRecordVerifier.FailureKind.ENCODING,
					excess.failure().kind());
		} finally {
			decoder.close();
		}
	}

	@Test
	public void testBadTriePathIsReported() throws Exception {
		EtchConfig config=EtchConfig.create(EtchConstants.VERSION_3);
		File file=tempFile("etch-strict-trie");
		AString root=rootBelowLastSlot("bad-trie");
		EtchStore store=new EtchStore(Etch.create(file,config));
		store.setRootData(root);
		store.flush();
		store.close();

		long slot;
		long pointer;
		try (EtchMaintenanceReader reader=EtchMaintenanceReader.openUnsafe(file,config)) {
			slot=reader.getIndexStart()
					+(root.getHash().shortAt(0)&0xffffL)*EtchConstants.POINTER_SIZE;
			pointer=reader.readIndexSlot(slot);
			assertEquals(0L,reader.readIndexSlot(slot+EtchConstants.POINTER_SIZE));
		}
		try (RandomAccessFile data=new RandomAccessFile(file,"rw")) {
			data.seek(slot);
			data.writeLong(0L);
			data.writeLong(pointer);
		}

		EtchStrictValidator.Report report=EtchStrictValidator.validate(file,config);
		assertFalse(report.isValid());
		assertTrue(report.problems().stream().anyMatch(p ->
				p.message().contains("does not match its index slot")),report.toString());
	}

	@Test
	public void testBrokenChainIsReported() throws Exception {
		EtchConfig config=EtchConfig.create(EtchConstants.VERSION_3);
		File file=tempFile("etch-strict-chain");
		AString root=rootBelowLastSlot("broken-chain");
		EtchStore store=new EtchStore(Etch.create(file,config));
		store.setRootData(root);
		store.flush();
		store.close();

		long slot;
		long pointer;
		try (EtchMaintenanceReader reader=EtchMaintenanceReader.openUnsafe(file,config)) {
			slot=reader.getIndexStart()
					+(root.getHash().shortAt(0)&0xffffL)*EtchConstants.POINTER_SIZE;
			pointer=reader.readIndexSlot(slot)&~EtchConstants.POINTER_TYPE_MASK;
		}
		try (RandomAccessFile data=new RandomAccessFile(file,"rw")) {
			data.seek(slot);
			data.writeLong(EtchConstants.POINTER_START|pointer);
		}

		EtchStrictValidator.Report report=EtchStrictValidator.validate(file,config);
		assertFalse(report.isValid());
		assertTrue(report.problems().stream().anyMatch(p ->
				p.kind()==EtchStrictValidator.ProblemKind.CHAIN),report.toString());
	}

	@Test
	public void testDuplicatePointerIsReported() throws Exception {
		EtchConfig config=EtchConfig.create(EtchConstants.VERSION_3);
		File file=tempFile("etch-strict-duplicate");
		AString root=rootBelowLastSlot("duplicate-pointer");
		EtchStore store=new EtchStore(Etch.create(file,config));
		store.setRootData(root);
		store.flush();
		store.close();

		long slot;
		long pointer;
		try (EtchMaintenanceReader reader=EtchMaintenanceReader.openUnsafe(file,config)) {
			slot=reader.getIndexStart()
					+(root.getHash().shortAt(0)&0xffffL)*EtchConstants.POINTER_SIZE;
			pointer=reader.readIndexSlot(slot);
			assertEquals(0L,reader.readIndexSlot(slot+EtchConstants.POINTER_SIZE));
		}
		try (RandomAccessFile data=new RandomAccessFile(file,"rw")) {
			data.seek(slot+EtchConstants.POINTER_SIZE);
			data.writeLong(pointer);
		}

		EtchStrictValidator.Report report=EtchStrictValidator.validate(file,config);
		assertFalse(report.isValid());
		assertTrue(report.problems().stream().anyMatch(p ->
				p.message().contains("duplicate data pointer")),report.toString());
	}

	@Test
	public void testMissingRootBranchIsReported() throws Exception {
		EtchConfig config=EtchConfig.create(EtchConstants.VERSION_3);
		File file=tempFile("etch-strict-missing");
		AString child=largeStringWithDifferentRootDigit("missing-child",null);
		var root=Vectors.of(child);
		while (root.getHash().shortAt(0)==child.getHash().shortAt(0)) {
			child=largeStringWithDifferentRootDigit("missing-child",child.getHash());
			root=Vectors.of(child);
		}
		EtchStore store=new EtchStore(Etch.create(file,config));
		store.setRootData(root);
		store.flush();
		store.close();

		long slotPosition;
		try (EtchMaintenanceReader reader=EtchMaintenanceReader.openUnsafe(file,config)) {
			slotPosition=reader.getIndexStart()
					+(child.getHash().shortAt(0)&0xffffL)*EtchConstants.POINTER_SIZE;
			assertTrue(reader.readIndexSlot(slotPosition)!=0L);
		}
		try (RandomAccessFile data=new RandomAccessFile(file,"rw")) {
			data.seek(slotPosition);
			data.writeLong(0L);
		}

		EtchStrictValidator.Report report=EtchStrictValidator.validate(file,config);
		assertFalse(report.isValid());
		assertEquals(1L,report.missingRootHashes(),report.problems().toString());
	}

	private static long findRootRecord(File file, EtchConfig config, Hash hash)
			throws IOException {
		try (EtchMaintenanceReader reader=EtchMaintenanceReader.openUnsafe(file,config)) {
			long slotPosition=reader.getIndexStart()
					+(hash.shortAt(0)&0xffffL)*EtchConstants.POINTER_SIZE;
			return reader.readIndexSlot(slotPosition)&~EtchConstants.POINTER_TYPE_MASK;
		}
	}

	private static AString largeStringWithDifferentRootDigit(String prefix, Hash excluded) {
		for (int i=0;;i++) {
			AString value=largeString(prefix+i);
			if ((excluded==null)||(value.getHash().shortAt(0)!=excluded.shortAt(0))) return value;
		}
	}

	private static AString largeString(String prefix) {
		return Strings.create((prefix+"-0123456789abcdef").repeat(12));
	}

	private static AString rootBelowLastSlot(String prefix) {
		for (int i=0;;i++) {
			AString root=largeString(prefix+i);
			if ((root.getHash().shortAt(0)&0xffff)<0xffff) return root;
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
