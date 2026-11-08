package convex.etch;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import convex.core.data.AString;
import convex.core.data.Strings;

/** Process-crash/filesystem acknowledgement matrix; not a claim about power-loss ordering. */
public class EtchGCFileFaultTest {
	@TempDir(cleanup=CleanupMode.NEVER) Path directory;
	@AfterEach public void removeFixtures() throws IOException {
		// Java 21 MBB mappings may outlive close on Windows. Do not turn the
		// explicitly supported deferred-deletion outcome into a fixture failure.
		try (var paths=Files.walk(directory)) {
			for (Path path:paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
				try { Files.deleteIfExists(path); }
				catch (IOException pinned) { path.toFile().deleteOnExit(); }
			}
		}
	}
	private static final byte[] KEY=new byte[32];
	private static final AString OLD=Strings.create("original-root-"+"o".repeat(200));
	private static final AString NEW=Strings.create("retained-root-"+"n".repeat(200));

	static Stream<EtchConfig> configs() {
		return Stream.of(EtchConfig.CipherMode.values()).flatMap(cipher->
				(cipher==EtchConfig.CipherMode.NONE?Stream.of(false):Stream.of(false,true))
				.map(index->EtchConfig.createV3(EtchConfig.create(EtchConstants.VERSION_3).getMappingMode(),
						true,cipher,index,null,cipher==EtchConfig.CipherMode.NONE?null:h->KEY.clone())));
	}

	private static class Faults extends EtchGCFileOps {
		final List<String> events=new ArrayList<>();
		int cut=-1;
		boolean after;
		boolean fired;
		boolean failCleanup;
		void arm(int cut,boolean after) { events.clear(); this.cut=cut; this.after=after; fired=false; }
		private void before(String event) throws IOException {
			events.add(event);
			if (!after) fail();
		}
		private void fail() throws IOException {
			if (!fired && events.size()-1==cut) { fired=true; throw new IOException("Injected "+events.getLast()); }
		}
		private void after() throws IOException { if (after) fail(); }
		@Override void checkpoint(Etch etch) throws IOException { before("checkpoint"); super.checkpoint(etch); after(); }
		@Override void link(Path name,Path source) throws IOException { before("snapshot-link"); super.link(name,source); after(); }
		@Override Path temporary(Path path) throws IOException { before("metadata-temp"); Path p=super.temporary(path); after(); return p; }
		@Override void writeAndForce(Path path,String contents) throws IOException { before("metadata-force"); super.writeAndForce(path,contents); after(); }
		@Override void replace(Path from,Path to) throws IOException { before("replace:"+to.getFileName()); super.replace(from,to); after(); }
		@Override void cleanup(Path path) throws IOException {
			if (fired && failCleanup) throw new IOException("Injected failed cleanup");
			before("cleanup:"+path.getFileName()); super.cleanup(path); after();
		}
		@Override boolean delete(File file) throws IOException { before("delete:"+file.getName()); boolean result=super.delete(file); after(); return result; }
		@Override void adopt(Path from,Path to) throws IOException { before("adopt"); super.adopt(from,to); after(); }
	}

	/** Preserve hard-link aliases, including an unconfirmed pre-commit snapshot. */
	private record Image(Map<String,byte[]> bytes,Map<String,String> aliases) {
		static Image capture(Path dir,EtchStore... stores) throws IOException {
			List<Etch> open=new ArrayList<>();
			for (EtchStore store:stores) {
				if (store==null) continue;
				open.add(store.getEtch());
				Etch target=store.getTargetEtch();
				if (target!=null) open.add(target);
			}
			Map<String,byte[]> bytes=new LinkedHashMap<>();
			Map<String,String> aliases=new LinkedHashMap<>();
			try (var names=Files.list(dir)) {
				for (Path path:names.sorted().toList()) {
					if (!Files.isRegularFile(path)) continue;
					String name=path.getFileName().toString();
					for (String previous:bytes.keySet()) {
						if (Files.isSameFile(path,dir.resolve(previous))) { aliases.put(name,previous); break; }
					}
					bytes.put(name,readImage(path,open));
				}
			}
			return new Image(bytes,aliases);
		}
		private static byte[] readImage(Path path,List<Etch> open) throws IOException {
			// On Windows a second file handle cannot read an exclusively locked
			// store. Snapshot through the test's existing owning channel instead.
			for (Etch etch:open) {
				if (!Files.isSameFile(path,etch.getFile().toPath())) continue;
				try {
					var field=Etch.class.getDeclaredField("data");
					field.setAccessible(true);
					var data=(java.io.RandomAccessFile)field.get(etch);
					byte[] bytes=new byte[Math.toIntExact(data.length())];
					var buffer=java.nio.ByteBuffer.wrap(bytes);
					while (buffer.hasRemaining()) {
						if (data.getChannel().read(buffer,buffer.position())<0) throw new IOException("Unexpected snapshot EOF");
					}
					return bytes;
				} catch (ReflectiveOperationException e) { throw new AssertionError(e); }
			}
			return Files.readAllBytes(path);
		}
		void restore(Path dir) throws IOException {
			Files.createDirectories(dir);
			for (var entry:bytes.entrySet()) {
				Path path=dir.resolve(entry.getKey());
				if (aliases.containsKey(entry.getKey())) Files.createLink(path,dir.resolve(aliases.get(entry.getKey())));
				else Files.write(path,entry.getValue());
			}
		}
		void assertUnchanged(Path dir) throws IOException {
			Image now=capture(dir);
			assertEquals(bytes.keySet(),now.bytes.keySet());
			for (String name:bytes.keySet()) assertArrayEquals(bytes.get(name),now.bytes.get(name),name);
			assertEquals(aliases,now.aliases);
		}
	}

	private EtchStore prepared(Path dir,EtchConfig config,Faults ops) throws IOException {
		Files.createDirectories(dir);
		EtchStore store=new EtchStore(Etch.create(dir.resolve("live.etch").toFile(),config),ops);
		store.setRootData(OLD);
		store.flush();
		store.startGC();
		store.setRootData(NEW);
		store.transferGC();
		return store;
	}

	private void reopenTwice(Path dir,EtchConfig config,boolean committed) throws IOException {
		AString root;
		try (EtchStore store=EtchStore.create(dir.resolve("live.etch").toFile(),config)) {
			root=store.getRootData();
			assertTrue(root.equals(OLD)||root.equals(NEW),"Never silently create an empty store");
			if (committed) assertEquals(NEW,root);
			assertTrue(EtchVerifier.findMissing(store.getEtch(),store.getRootHash()).isEmpty());
		}
		try (EtchStore store=EtchStore.create(dir.resolve("live.etch").toFile(),config)) { assertEquals(root,store.getRootData()); }
	}

	@ParameterizedTest @MethodSource("configs")
	public void cutoverBeforeAndAfterEveryFilesystemOperation(EtchConfig config) throws Exception {
		Faults trace=new Faults();
		Path baseline=Files.createDirectory(directory.resolve("trace"));
		EtchStore original=prepared(baseline,config,trace);
		trace.arm(-1,false);
		EtchStore current=original.completeGC(baseline.resolve("snapshot.etch").toFile());
		int count=trace.events.size();
		current.close(); original.close();
		for (int cut=0;cut<count;cut++) for (boolean after:new boolean[] {false,true}) {
			Path dir=Files.createDirectory(directory.resolve("cut-"+cut+"-"+after));
			Faults ops=new Faults();
			EtchStore source=prepared(dir,config,ops);
			EtchStore next=null;
			ops.arm(cut,after);
			boolean committed=false;
			try { next=source.completeGC(dir.resolve("snapshot.etch").toFile()); committed=true; }
			catch (IOException expected) { assertTrue(ops.fired); }
			Image image=Image.capture(dir,source,next);
			if (next!=null) next.close(); source.close();
			Path restart=directory.resolve("restart-"+cut+"-"+after);
			image.restore(restart);
			reopenTwice(restart,config,committed);
			if (committed) {
				try (EtchStore snapshot=EtchStore.create(restart.resolve("snapshot.etch").toFile(),config)) { assertEquals(OLD,snapshot.getRootData()); }
			}
		}
	}

	@ParameterizedTest @MethodSource("configs")
	public void recoveryInterruptedAtEveryMutationAndAcknowledgement(EtchConfig config) throws Exception {
		Path source=Files.createDirectory(directory.resolve("source"));
		EtchStore old=prepared(source,config,new Faults());
		EtchStore next=old.completeGC(source.resolve("snapshot.etch").toFile());
		Image committed=Image.capture(source,old,next);
		next.close(); old.close();
		Path traceDir=directory.resolve("trace"); committed.restore(traceDir);
		Faults trace=new Faults();
		EtchUtils.recover(traceDir.resolve("live.etch").toFile(),config,trace);
		for (int cut=0;cut<trace.events.size();cut++) for (boolean after:new boolean[] {false,true}) {
			Path dir=directory.resolve("recovery-"+cut+"-"+after); committed.restore(dir);
			Faults ops=new Faults(); ops.arm(cut,after);
			try { EtchUtils.recover(dir.resolve("live.etch").toFile(),config,ops); }
			catch (IOException expected) { assertTrue(ops.fired); }
			reopenTwice(dir,config,true);
			try (EtchStore snapshot=EtchStore.create(dir.resolve("snapshot.etch").toFile(),config)) { assertEquals(OLD,snapshot.getRootData()); }
		}
	}

	@ParameterizedTest @MethodSource("configs")
	public void malformedMissingReusedAndDirtyLayoutsPreserveEvidence(EtchConfig config) throws Exception {
		for (String fault:List.of("malformed","missing","reused","dirty")) {
			Path dir=Files.createDirectory(directory.resolve(fault));
			EtchStore old=prepared(dir,config,new Faults());
			EtchStore next=old.completeGC();
			if (fault.equals("dirty")) next.setRootData(Strings.create("uncheckpointed"));
			Image committed=Image.capture(dir,old,next);
			next.close(); old.close();
			Path restart=directory.resolve(fault+"-restart"); committed.restore(restart);
			Path marker=restart.resolve("live.etch.gc-complete");
			String target=Files.readAllLines(marker).getFirst();
			if (fault.equals("malformed")) Files.writeString(marker,"");
			if (fault.equals("missing")||fault.equals("reused")) {
				Files.delete(restart.resolve(target));
				if (fault.equals("reused")) {
					try (EtchStore replacement=EtchStore.create(restart.resolve(target).toFile(),config)) { replacement.setRootData(NEW); }
				}
			}
			Image before=Image.capture(restart);
			assertThrows(IOException.class,()->EtchUtils.recover(restart.resolve("live.etch").toFile(),config));
			before.assertUnchanged(restart);
			assertThrows(IOException.class,()->EtchUtils.recover(restart.resolve("live.etch").toFile(),config));
			before.assertUnchanged(restart);
		}
	}

	@ParameterizedTest @MethodSource("configs")
	public void cancellationRetryRetirementAndRepeatedCycles(EtchConfig config) throws Exception {
		for (boolean cancel:new boolean[] {false,true}) {
			Path dir=Files.createDirectory(directory.resolve("lifecycle-"+cancel));
			Faults ops=new Faults();
			EtchStore live=prepared(dir,config,ops);
			try {
				if (cancel) {
					ops.arm(0,false);
					assertThrows(IOException.class,live::cancelGC);
					ops.arm(-1,false);
					live.cancelGC();
					assertEquals(NEW,live.getRootData());
					live.startGC(); live.transferGC();
				}
				EtchStore next=live.completeGC(dir.resolve("confirmed.etch").toFile());
				ops.arm(0,true); // retirement unlink succeeds but acknowledgement fails
				live.close();
				live=next;
				ops.arm(-1,false);
				for (int cycle=0;cycle<3;cycle++) {
					live.setRootData(NEW);
					live.startGC(); live.transferGC();
					next=live.completeGC(); live.close(); live=next;
				}
			} finally { live.close(); }
			reopenTwice(dir,config,true);
			try (EtchStore backup=EtchStore.create(dir.resolve("confirmed.etch").toFile(),config)) {
				assertEquals(cancel?NEW:OLD,backup.getRootData());
			}
		}
	}

	@ParameterizedTest @MethodSource("configs")
	public void failedCleanupAndCutoverRetry(EtchConfig config) throws Exception {
		Path dir=Files.createDirectory(directory.resolve("retry"));
		Faults ops=new Faults();
		EtchStore source=prepared(dir,config,ops);
		try {
			ops.arm(2,true); // snapshot hard link succeeded, acknowledgement failed
			ops.failCleanup=true;
			File backup=dir.resolve("unconfirmed.etch").toFile();
			IOException failure=assertThrows(IOException.class,()->source.completeGC(backup));
			assertTrue(failure.getSuppressed().length>0);
			assertTrue(backup.exists());
			assertTrue(source.isGCInProgress());
			// This path is explicitly unconfirmed and cannot be reused/overwritten.
			assertThrows(IOException.class,()->source.completeGC(backup));
			ops.failCleanup=false; ops.arm(-1,false);
			try (EtchStore next=source.completeGC(dir.resolve("confirmed.etch").toFile())) { assertEquals(NEW,next.getRootData()); }
		} finally { source.close(); }
		reopenTwice(dir,config,true);
	}

	@ParameterizedTest @MethodSource("configs")
	public void unreadableMarkerAfterLostPublicationAcknowledgementPreservesEvidence(EtchConfig config) throws Exception {
		Path dir=Files.createDirectory(directory.resolve("unreadable-marker"));
		Faults ops=new Faults() {
			@Override void replace(Path from,Path to) throws IOException {
				super.replace(from,to);
				if (to.getFileName().toString().endsWith(".gc-complete")) throw new IOException("Lost marker acknowledgement");
			}
			@Override String readMetadata(Path path) throws IOException {
				throw new java.nio.file.AccessDeniedException(path.toString());
			}
		};
		EtchStore source=prepared(dir,config,ops);
		Image image;
		try {
			IOException failure=assertThrows(IOException.class,()->source.completeGC(dir.resolve("snapshot.etch").toFile()));
			assertEquals("Lost marker acknowledgement",failure.getMessage());
			assertEquals(1,failure.getSuppressed().length);
			assertInstanceOf(java.nio.file.AccessDeniedException.class,failure.getSuppressed()[0]);
			assertTrue(Files.exists(dir.resolve("live.etch.gc-complete")));
			assertTrue(Files.exists(dir.resolve("live.etch.gc-defunct")));
			assertTrue(Files.isSameFile(dir.resolve("snapshot.etch"),dir.resolve("live.etch")));
			image=Image.capture(dir,source);
		} finally { source.close(); }
		Path restart=directory.resolve("readable-restart"); image.restore(restart);
		reopenTwice(restart,config,true);
		try (EtchStore snapshot=EtchStore.create(restart.resolve("snapshot.etch").toFile(),config)) {
			assertEquals(OLD,snapshot.getRootData());
		}
	}

	@Test public void metadataCleanupFailureDoesNotHidePublicationFailure() throws Exception {
		Path target=directory.resolve("marker");
		Faults ops=new Faults(); ops.arm(1,false); ops.failCleanup=true;
		IOException failure=assertThrows(IOException.class,()->ops.metadata(target,"complete contents"));
		assertEquals("Injected metadata-force",failure.getMessage());
		assertEquals(1,failure.getSuppressed().length);
		assertFalse(Files.exists(target));
	}

	@Test public void wrongKeyRejectsBeforeAnyLayoutMutation() throws Exception {
		EtchConfig encrypted=configs().filter(c->c.getCipherMode()!=EtchConfig.CipherMode.NONE).findFirst().orElseThrow();
		Path dir=Files.createDirectory(directory.resolve("encrypted"));
		EtchStore source=prepared(dir,encrypted,new Faults());
		EtchStore next=source.completeGC();
		Image image=Image.capture(dir,source,next); next.close(); source.close();
		Path restart=directory.resolve("wrong-key"); image.restore(restart);
		byte[] wrong=KEY.clone(); wrong[0]=1;
		EtchConfig config=EtchConfig.createV3(EtchConfig.MappingMode.MAPPED_BYTE_BUFFER,true,
				EtchConfig.CipherMode.AES_256_CTR,false,null,h->wrong);
		assertThrows(IOException.class,()->EtchUtils.recover(restart.resolve("live.etch").toFile(),config));
		image.assertUnchanged(restart);
	}

	@Test public void markerGrammarCannotSilentlyDowngradeAndBaseIdentityIsChecked() throws Exception {
		EtchConfig config=configs().findFirst().orElseThrow();
		Path dir=Files.createDirectory(directory.resolve("grammar"));
		EtchStore old=prepared(dir,config,new Faults());
		EtchStore next=old.completeGC();
		Image image=Image.capture(dir,old,next); next.close(); old.close();
		String marker=new String(image.bytes.get("live.etch.gc-complete"),java.nio.charset.StandardCharsets.UTF_8);
		List<String> malformed=List.of(
				marker.replace("etch-gc-v1","etch-gc-v"),
				marker.replace("v3-salt ","v3-sal "),
				marker.substring(0,marker.lastIndexOf("attempt ")),
				marker+"unexpected extra line\n",
				marker.lines().findFirst().orElseThrow()+"\nold informational line\n",
				"live.etch\n"+marker.substring(marker.indexOf('\n')+1));
		int index=0;
		for (String contents:malformed) {
			Path restart=directory.resolve("invalid-"+index++); image.restore(restart);
			Files.writeString(restart.resolve("live.etch.gc-complete"),contents);
			Image before=Image.capture(restart);
			assertThrows(IOException.class,()->EtchUtils.recover(restart.resolve("live.etch").toFile(),config));
			before.assertUnchanged(restart);
		}
		Path legacy=directory.resolve("legacy"); image.restore(legacy);
		Files.writeString(legacy.resolve("live.etch.gc-complete"),marker.lines().findFirst().orElseThrow()+"\n");
		reopenTwice(legacy,config,true);
	}

	@Test public void deferredAdoptionAndBackupSidecars() throws Exception {
		EtchConfig config=configs().findFirst().orElseThrow();
		Path dir=Files.createDirectory(directory.resolve("deferred"));
		EtchStore old=prepared(dir,config,new Faults());
		EtchStore next=old.completeGC();
		Image committed=Image.capture(dir,old,next); next.close(); old.close();
		Path restart=directory.resolve("restart"); committed.restore(restart);
		File base=restart.resolve("live.etch").toFile();
		File selected=EtchUtils.recover(base,config,new EtchGCFileOps() {
			@Override boolean delete(File file) throws IOException { return file.equals(base)?false:super.delete(file); }
		});
		assertNotEquals(base,selected);
		try (EtchStore live=EtchStore.create(selected,config)) { assertEquals(NEW,live.getRootData()); }
		reopenTwice(restart,config,true);
		for (String suffix:List.of(".gc-complete",".gc-defunct","~")) {
			Path d=Files.createDirectory(directory.resolve("sidecar"+suffix));
			try (EtchStore store=prepared(d,config,new Faults())) {
				Path backup=d.resolve("snapshot.etch");
				Path sidecar=Files.writeString(d.resolve("snapshot.etch"+suffix),"retain evidence");
				assertThrows(IOException.class,()->store.completeGC(backup.toFile()));
				assertFalse(Files.exists(backup)); assertEquals("retain evidence",Files.readString(sidecar));
			}
		}
	}
}
