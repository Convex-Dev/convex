package convex.lattice.fs;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Index;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.lattice.LatticeContext;

public class DLFSAttributesTest {
	@Test
	public void testOptionalMtimeRepresentations() {
		var time=CVMLong.create(100);
		var tombs=Index.of(Strings.create("deleted"),time);
		var legacyNodes=java.util.List.of(DLFSNode.createEmptyFile(time),
			DLFSNode.createDirectory(time),DLFSNode.createDirectory(time).conj(tombs));
		for (var legacy:legacyNodes) {
			var explicit=DLFSNode.withMTime(legacy,time);
			var implicit=DLFSNode.withMTime(explicit,null);
			var truncated=explicit.slice(0,legacy.count());
			assertEquals(legacy,truncated);
			for (var node:java.util.List.of(explicit,implicit,truncated)) {
				assertTrue(DLFSNode.isValidTree(node));
				assertEquals(time,DLFSNode.getMTime(node));
				assertEquals(DLFSNode.getTombstones(legacy),DLFSNode.getTombstones(node));
				var edited=DLFSNode.withMetadata(node,Strings.create("metadata"),CVMLong.create(200));
				assertEquals(time,DLFSNode.getMTime(edited),"advancing utime must preserve the old fallback");
			}
			var future=implicit.conj(Strings.create("future"));
			assertTrue(DLFSNode.isValidTree(future));
			assertEquals(time,DLFSNode.getMTime(future));
			assertEquals(future.get(6),DLFSNode.withMTime(future,null).get(6));
		}
	}

	@Test
	public void testDirectoryTimesTrackImmediateEntriesOnly() throws Exception {
		try (var fs=DLFS.create()) {
			fs.setContext(LatticeContext.create(CVMLong.create(100),null));
			var dir=Files.createDirectory(fs.getPath("/dir"));
			fs.setContext(LatticeContext.create(CVMLong.create(200),null));
			var file=Files.writeString(dir.resolve("file"),"first");
			assertEquals(100,Files.getLastModifiedTime(fs.getRoot()).toMillis());
			assertEquals(200,Files.getLastModifiedTime(dir).toMillis());
			fs.setContext(LatticeContext.create(CVMLong.create(300),null));
			Files.writeString(file,"second");
			assertEquals(100,Files.getLastModifiedTime(fs.getRoot()).toMillis());
			assertEquals(200,Files.getLastModifiedTime(dir).toMillis());
			assertEquals(300,DLFSNode.getUTime(fs.getCursor().get()).longValue());
			Files.move(file,dir.resolve("renamed"));
			assertEquals(300,Files.getLastModifiedTime(dir).toMillis());
			assertEquals(100,Files.getLastModifiedTime(fs.getRoot()).toMillis());
			fs.setContext(LatticeContext.create(CVMLong.create(400),null));
			Files.delete(dir.resolve("renamed"));
			assertEquals(400,Files.getLastModifiedTime(dir).toMillis());
			assertEquals(100,Files.getLastModifiedTime(fs.getRoot()).toMillis());
		}
	}

	@Test
	public void testMergeUsesUpdateTimeAndPreservesOwnOnTies() {
		var first=DLFSNode.withMTime(DLFSNode.createDirectory(CVMLong.create(100)),CVMLong.create(500));
		var second=DLFSNode.withMTime(DLFSNode.createDirectory(CVMLong.create(100)),CVMLong.create(10));
		assertEquals(first,DLFSNode.merge(first,second));
		assertEquals(second,DLFSNode.merge(second,first));
		var newer=second.assoc(DLFSNode.POS_UTIME,CVMLong.create(200));
		assertEquals(newer,DLFSNode.merge(first,newer));
		assertEquals(newer,DLFSNode.merge(newer,first));
		var legacy=DLFSNode.createDirectory(CVMLong.create(300));
		assertEquals(legacy,DLFSNode.merge(newer,legacy),"merge must not synthesise extension fields");
	}

	@Test
	public void testIndependentTimesAndContentChanges() throws Exception {
		try (var fs=DLFS.create()) {
			fs.setContext(LatticeContext.create(CVMLong.create(100),null));
			var path=fs.getPath("/file");
			Files.writeString(path,"first");
			var view=Files.getFileAttributeView(path,BasicFileAttributeView.class);
			assertNotNull(view);
			view.setTimes(FileTime.fromMillis(10),FileTime.fromMillis(20),FileTime.fromMillis(30));
			var attrs=view.readAttributes();
			assertEquals(10,attrs.lastModifiedTime().toMillis());
			assertEquals(10,attrs.lastAccessTime().toMillis());
			assertEquals(0,attrs.creationTime().toMillis());
			var before=fs.getCursor().get();
			view.setTimes(null,FileTime.fromMillis(21),FileTime.fromMillis(31));
			assertSame(before,fs.getCursor().get(),"unsupported timestamps must not cause writes");
			assertEquals(6,fs.getNode(path).count(),"mtime is the only timestamp extension");
			assertEquals(CVMLong.create(100),DLFSNode.getUTime(fs.getNode(path)));
			fs.setContext(LatticeContext.create(CVMLong.create(200),null));
			Files.writeString(path,"second");
			attrs=view.readAttributes();
			assertEquals(200,attrs.lastModifiedTime().toMillis());
			assertEquals(200,attrs.lastAccessTime().toMillis());
			assertEquals(0,attrs.creationTime().toMillis());
			Files.setAttribute(path,"basic:lastModifiedTime",FileTime.fromMillis(11));
			assertEquals(FileTime.fromMillis(11),Files.readAttributes(path,"basic:lastModifiedTime,size").get("lastModifiedTime"));
			assertEquals(6L,Files.getAttribute(path,"size"));
			assertEquals(9,Files.readAttributes(path,"*").size());
		}
	}

	@Test
	public void testCopyAndMovePreserveRequestedTimes() throws Exception {
		try (var source=DLFS.create(); var destination=DLFS.create()) {
			var file=source.getPath("/file");
			Files.writeString(file,"data");
			Files.getFileAttributeView(file,BasicFileAttributeView.class).setTimes(
				FileTime.fromMillis(10),FileTime.fromMillis(20),FileTime.fromMillis(30));
			destination.setContext(LatticeContext.create(CVMLong.create(200),null));
			Files.copy(file,destination.getPath("/copy"),StandardCopyOption.COPY_ATTRIBUTES);
			var attrs=Files.readAttributes(destination.getPath("/copy"),BasicFileAttributes.class);
			assertEquals(10,attrs.lastModifiedTime().toMillis());
			assertEquals(0,attrs.creationTime().toMillis());
			Files.move(file,destination.getPath("/moved"));
			assertEquals(FileTime.fromMillis(10),Files.getLastModifiedTime(destination.getPath("/moved")));
			assertEquals(CVMLong.create(200),DLFSNode.getUTime(destination.getNode(destination.getPath("/moved"))));
		}
	}

	@Test
	public void testLegacyReadAndUnknownFieldsSurviveMutationsAndMerge() throws Exception {
		try (var fs=DLFS.create()) {
			AVector<ACell> legacy=DLFSNode.createDirectory(CVMLong.create(100));
			assertEquals(4,legacy.count());
			fs.getCursor().set(legacy);
			assertEquals(FileTime.fromMillis(100),Files.getLastModifiedTime(fs.getRoot()));
			assertSame(legacy,fs.getCursor().get(),"reading must not upgrade legacy encodings");
			var unknown=Strings.create("future extension");
			var extended=DLFSNode.withMTime(legacy,CVMLong.create(100)).conj(unknown);
			assertTrue(DLFSNode.isValidTree(extended));
			fs.getCursor().set(extended);
			Files.writeString(fs.getPath("/child"),"data");
			Files.delete(fs.getPath("/child"));
			assertSame(unknown,fs.getCursor().get().get(6));
			Files.writeString(fs.getPath("/child"),"again");
			assertSame(unknown,fs.getCursor().get().get(6));
			assertNull(fs.getCursor().get().get(DLFSNode.POS_TOMBS));
			assertTrue(DLFSNode.isValidTree(fs.getCursor().get()));
			var merged=DLFSLattice.INSTANCE.merge(fs.getCursor().get(),legacy);
			assertSame(unknown,merged.get(6));
			Files.setLastModifiedTime(fs.getRoot(),FileTime.fromMillis(17));
			assertSame(unknown,fs.getCursor().get().get(6));
		}
	}

	@Test
	public void testFileExtensionsSurviveContentMetadataAndTransferChanges() throws Exception {
		try (var fs=DLFS.create(); var destination=DLFS.create()) {
			fs.setContext(LatticeContext.create(CVMLong.create(100),null));
			var file=fs.getPath("/file");
			Files.writeString(file,"initial");
			var extension=Strings.create("unknown file extension");
			var node=fs.getNode(file).conj(extension);
			fs.updateNode(file,node);
			fs.setContext(LatticeContext.create(CVMLong.create(200),null));
			Files.writeString(file,"changed");
			node=DLFSNode.withMetadata(fs.getNode(file),Strings.create("metadata"),CVMLong.create(300));
			assertEquals(CVMLong.create(200),DLFSNode.getMTime(node));
			fs.updateNode(file,node);
			Files.copy(file,destination.getPath("/copy"));
			assertEquals(extension,destination.getNode(destination.getPath("/copy")).get(6));
			Files.move(file,fs.getPath("/renamed"));
			assertEquals(extension,fs.getNode(fs.getPath("/renamed")).get(6));
			assertEquals(200,Files.getLastModifiedTime(fs.getPath("/renamed")).toMillis());
		}
	}

	@Test
	public void testDirectoryMergePreservesWinningExtensionsWhileCombiningChildren() throws Exception {
		try (var own=DLFS.create(); var other=DLFS.create()) {
			own.setContext(LatticeContext.create(CVMLong.create(200),null));
			other.setContext(LatticeContext.create(CVMLong.create(100),null));
			Files.writeString(own.getPath("/own"),"own");
			Files.writeString(other.getPath("/other"),"other");
			var extension=Strings.create("unknown directory extension");
			own.getCursor().set(own.getCursor().get().conj(extension));
			own.merge(other.getCursor().get());
			assertEquals("own",Files.readString(own.getPath("/own")));
			assertEquals("other",Files.readString(own.getPath("/other")));
			assertEquals(extension,own.getCursor().get().get(6));
		}
	}
}
