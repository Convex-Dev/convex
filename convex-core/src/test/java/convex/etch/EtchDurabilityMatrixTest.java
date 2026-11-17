package convex.etch;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Ref;
import convex.core.data.Strings;
import convex.core.data.Vectors;

/** Deterministic cuts in actual Etch operations, never timing-dependent JVM kills. */
public class EtchDurabilityMatrixTest {
	private static final byte[] KEY=new byte[32];
	private static final byte[] SALT=new byte[32];
	static { Arrays.fill(KEY,(byte)37); Arrays.fill(SALT,(byte)19); }
	@TempDir(cleanup=CleanupMode.NEVER) Path directory;
	private int imageNumber;
	@AfterEach public void removeFixtures() throws IOException {
		try (var paths=Files.walk(directory)) {
			for (Path path:paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
				try { Files.deleteIfExists(path); }
				catch (IOException pinned) { path.toFile().deleteOnExit(); }
			}
		}
	}

	static Stream<EtchConfig> configs() {
		return Stream.of(EtchConfig.CipherMode.values()).flatMap(cipher->
				(cipher==EtchConfig.CipherMode.NONE?Stream.of(false):Stream.of(false,true))
				.map(index->EtchConfig.createV3(EtchConfig.create(EtchConstants.VERSION_3).getMappingMode(),
						false,cipher,index,null,cipher==EtchConfig.CipherMode.NONE?null:h->KEY.clone())));
	}

	private record Fixture(DurableEtchFileMapper mapper,EtchV3Header header,EtchStore store) {}
	private Fixture fixture(EtchConfig config,byte[] image) throws Exception {
		DurableEtchFileMapper mapper=new DurableEtchFileMapper(image);
		byte[] key=config.getCipherMode()==EtchConfig.CipherMode.NONE?null:KEY;
		EtchV3Header header=image.length==0
				?EtchV3Header.create(config.getCipherMode().fileId(),config.isIndexEncrypted(),SALT,key)
				:select(image,key);
		EtchFileCipher cipher=switch(config.getCipherMode()) {
			case NONE -> null;
			case AES_256_CTR -> AES256CTREtchCipher.derive(KEY,header.fileSalt());
			case CHACHA20 -> ChaCha20EtchCipher.derive(KEY,header.fileSalt());
		};
		Etch etch=new Etch(mapper,"durability-matrix",cipher,config.isIndexEncrypted()?cipher:null,header,config);
		if (image.length==0) header.initialise(etch);
		else mapper.adoptLength(header.syncedFileEnd());
		return new Fixture(mapper,header,new EtchStore(etch));
	}

	private static EtchV3Header select(byte[] image,byte[] key) throws IOException {
		return EtchV3Header.select(Arrays.copyOfRange(image,0,4096),
				Arrays.copyOfRange(image,4096,8192),key,"crash-image");
	}

	private static AString[] collision() {
		Map<Integer,AString> seen=new HashMap<>();
		for (int i=0;i<10000;i++) {
			AString value=Strings.create("collision-"+i+"-"+"x".repeat(200));
			int slot=EtchConstants.indexDigit(value.getHash(),0);
			AString previous=seen.putIfAbsent(slot,value);
			if (previous!=null) return new AString[] {previous,value};
		}
		throw new AssertionError("Deterministic collision fixture not found");
	}

	private static void mutate(Fixture f,AString[] values,boolean close) throws IOException {
		// Chains disabled: these genuine colliding hashes require child-index publication.
		f.store.setRootData(Vectors.of(values[0],values[1]));
		f.store.storeTopRef(values[0].getRef(),Ref.ANNOUNCED,null);
		if (close) f.store.getEtch().closeChecked();
		else f.store.flush();
	}

	@ParameterizedTest @MethodSource("configs")
	public void cutsAcrossMutationSyncAndClose(EtchConfig config) throws Exception {
		AString[] values=collision();
		byte[] seed;
		Fixture initial=fixture(config,new byte[0]);
		try {
			initial.store.setRootData(values[0]);
			initial.store.flush();
			seed=initial.mapper.image();
		} finally { initial.store.close(); }
		for (boolean close:new boolean[] {false,true}) {
			Fixture trace=fixture(config,seed);
			trace.mapper.arm(-1,0);
			mutate(trace,values,close);
			var events=java.util.List.copyOf(trace.mapper.events);
			assertTrue(events.stream().anyMatch(e->e.kind()==DurableEtchFileMapper.Kind.SLOT_WRITE));
			assertTrue(events.stream().anyMatch(e->e.kind()==DurableEtchFileMapper.Kind.BODY_FORCE));
			assertEquals(Vectors.of(values[0],values[1]).getHash(),select(trace.mapper.image(),key(config)).getRootHash());
			checkImage(config,trace.mapper.image());
			trace.store.close();
			for (int cut=0;cut<events.size();cut++) {
				var event=events.get(cut);
				for (int prefix:new int[] {0,event.length()/2,event.length()}) {
					Fixture f=fixture(config,seed);
					f.mapper.arm(cut,prefix);
					try {
						assertThrows(IOException.class,()->mutate(f,values,close),event.toString());
						byte[] image=f.mapper.image();
						// Failed publication must not advance the in-memory header generation.
						long expected=event.kind()==DurableEtchFileMapper.Kind.HEADER_WRITE && cut==0
								||event.kind()==DurableEtchFileMapper.Kind.HEADER_FORCE && cut==1?2:3;
						assertEquals(expected,f.header.generation(),event.toString());
						if (cut<2) assertEquals(seed.length,f.store.getEtch().getDataLength(),"No mutation before OPEN force");
						checkImage(config,image);
					} finally {
						try { f.store.getEtch().closeChecked(); } catch (IOException expected) { /* crashed storage */ }
						assertTrue(f.mapper.closed,"Close failure must still release mapping");
					}
				}
			}
		}
	}

	private static byte[] key(EtchConfig config) { return config.getCipherMode()==EtchConfig.CipherMode.NONE?null:KEY; }

	private void checkImage(EtchConfig config,byte[] image) throws Exception {
		EtchV3Header selected=select(image,key(config));
		int number=imageNumber++;
		Path source=directory.resolve("source-"+number+".etch");
		Path repaired=directory.resolve("repaired-"+number+".etch");
		Files.write(source,image);
		try {
			if (selected.isCleanClosed()) {
				try (EtchStore store=EtchStore.create(source.toFile(),config)) {
					assertEquals(selected.getRootHash(),store.getRootHash());
					assertTrue(EtchVerifier.findMissing(store.getEtch(),store.getRootHash()).isEmpty());
				}
			} else {
				assertThrows(IOException.class,()->EtchStore.create(source.toFile(),config));
			}
			byte[] before=Files.readAllBytes(source);
			EtchRebuilder.Result result=EtchRebuilder.rebuild(source.toFile(),config,repaired.toFile(),config);
			assertArrayEquals(before,Files.readAllBytes(source),"Repair never changes source evidence");
			assertEquals(EtchRebuilder.Status.COMPLETE,result.status());
			try (EtchStore store=EtchStore.create(repaired.toFile(),config)) {
				assertEquals(selected.getRootHash(),store.getRootHash(),"Tail never supplies a newer root");
				assertTrue(EtchVerifier.findMissing(store.getEtch(),store.getRootHash()).isEmpty());
			}
		} finally {
			selected.destroy();
			// MBB close cannot synchronously unmap on Java 21/Windows. Names are
			// unique per crash image; deferred fixture cleanup does not affect replay.
			try { Files.deleteIfExists(source); } catch (IOException pinned) { source.toFile().deleteOnExit(); }
			try { Files.deleteIfExists(repaired); } catch (IOException pinned) { repaired.toFile().deleteOnExit(); }
		}
	}

	@ParameterizedTest @MethodSource("configs")
	public void validTailAndIncompleteRootRepair(EtchConfig config) throws Exception {
		Fixture f=fixture(config,new byte[0]);
		AString root=Strings.create("checkpoint-"+"r".repeat(200));
		AString tail=Strings.create("unsynchronised-"+"t".repeat(200));
		try {
			f.store.setRootData(root);
			f.store.flush();
			f.store.storeTopRef(tail.getRef(),Ref.STORED,null);
			f.mapper.force(); // unsolicited body writeback, without a new clean checkpoint
			Path source=directory.resolve("tail.etch");
			Path destination=directory.resolve("salvaged.etch");
			byte[] image=f.mapper.image();
			Files.write(source,image);
			EtchRebuilder.Result result=EtchRebuilder.rebuild(source.toFile(),config,destination.toFile(),config);
			assertEquals(EtchRebuilder.Status.COMPLETE,result.status());
			assertArrayEquals(image,Files.readAllBytes(source));
			try (EtchStore restored=EtchStore.create(destination.toFile(),config)) {
				assertEquals(root,restored.getRootData());
				assertEquals(tail,restored.getEtch().read(tail.getHash()).getValue());
			}
			source=directory.resolve("incomplete.etch");
			destination=directory.resolve("partial.etch");
			ACell incomplete=Vectors.of(root,Strings.create("missing-"+"m".repeat(200)));
			f.store.getEtch().write(incomplete.getHash(),incomplete.getRef().withStatus(Ref.STORED));
			f.store.getEtch().setRootHash(incomplete.getHash());
			f.store.flush();
			image=f.mapper.image();
			Files.write(source,image);
			result=EtchRebuilder.rebuild(source.toFile(),config,destination.toFile(),config);
			assertEquals(EtchRebuilder.Status.PARTIAL,result.status());
			assertFalse(result.isRootComplete());
			assertArrayEquals(image,Files.readAllBytes(source));
			try (EtchStore restored=EtchStore.create(destination.toFile(),config)) {
				assertEquals(convex.core.data.Hash.UNSET_HASH,restored.getRootHash());
			}
		} finally { f.store.close(); }
	}

	@ParameterizedTest @MethodSource("configs")
	public void closeReleasesResourcesAfterCheckpointAndCleanupFailures(EtchConfig config) throws Exception {
		Fixture f=fixture(config,new byte[0]);
		f.store.setRootData(Strings.create("close-failure"));
		f.mapper.arm(0,0); // body force fails
		f.mapper.failClose=true;
		IOException failure=assertThrows(IOException.class,()->f.store.getEtch().closeChecked());
		assertTrue(f.mapper.closed);
		assertEquals(1,failure.getCause().getSuppressed().length);
		assertFalse(f.header.isCleanClosed());
		assertEquals(1,f.header.generation());
		assertTrue(f.store.getEtch().isCryptoDestroyed());
		f.store.getEtch().closeChecked(); // repeated cleanup is harmless
	}

	@ParameterizedTest @MethodSource("configs")
	public void everyHeaderPrefixAndUnforcedWriteback(EtchConfig config) throws Exception {
		Fixture f=fixture(config,new byte[0]);
		try {
			f.store.setRootData(Strings.create("old"));
			f.store.flush();
			byte[] old=f.mapper.image();
			long generation=f.header.generation();
			byte[] next=f.header.encode(generation+1,f.header.syncedFileEnd(),f.header.getRootHash(),EtchConstants.V3_OPEN);
			int position=(1-f.header.activeCopy())*4096;
			// Every prefix through the used fields, and every prefix through the check.
			// Zero padding has no other byte-distinct states.
			for (int prefix=0;prefix<=4096;prefix++) {
				if (prefix>EtchConstants.V3_HEADER_PREFIX_SIZE && prefix<EtchConstants.V3_HEADER_CHECK_OFFSET) continue;
				byte[] torn=old.clone();
				System.arraycopy(next,0,torn,position,prefix);
				EtchV3Header selected=select(torn,key(config));
				assertEquals(prefix==4096?generation+1:generation,selected.generation(),"prefix="+prefix);
				selected.destroy();
			}
			f.mapper.arm(1,0); // complete live OPEN header, failed force with no writeback
			assertThrows(IOException.class,()->f.store.setRootData(Strings.create("new")));
			assertArrayEquals(old,f.mapper.image());
			assertFalse(Arrays.equals(old,f.mapper.liveImage()));
			assertEquals(generation,f.header.generation());
		} finally { try { f.store.getEtch().closeChecked(); } catch (IOException expected) { } }
	}
}
