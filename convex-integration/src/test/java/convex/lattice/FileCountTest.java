package convex.lattice;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.cvm.Keywords;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.SignedData;
import convex.core.data.prim.CVMLong;
import convex.dlfs.DLFSDrive;
import convex.dlfs.DLFSRegion;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.Root;

/**
 * An application's state committed with files: a root application serving DLFS
 * drives at {@code :fs} and, in its {@code :app} region, a count of files written,
 * so one transaction on the root writes a file and counts it.
 */
class FileCountTest {

	static final AKeyPair OWNER=AKeyPair.createSeeded(7401);

	/** The application's name under {@code :app}. */
	static final Keyword FILE_COUNT=Keyword.intern("file-count");

	/** Logical time for file writes, which keeps values deterministic. */
	static final long TIME=1000;

	/** A count, made on demand over its place. */
	static final class Counter extends ALatticeComponent<CVMLong> {
		Counter(AForkableCursor<CVMLong> cursor) { super(cursor); }

		long count() {
			CVMLong n=cursor().getOrDefault(null);
			return (n==null)?0:n.longValue();
		}

		void increment() {
			cursor().updateAndGet(n -> CVMLong.create(((n==null)?0:n.longValue())+1));
		}
	}

	/** The application's own data at {@code :app}, which makes the count on its entry. */
	static final class AppData extends IndexComponent<Keyword,ACell> {
		AppData(AForkableCursor<Index<Keyword,ACell>> cursor) { super(cursor); }
		Counter filesWritten() { return new Counter(entry(FILE_COUNT)); }
	}

	/** The root application: files at {@code :fs}, and the count at {@code [:app :file-count]}. */
	static final class FilesApp extends Regions implements TransactionalComponent<Index<Keyword,ACell>,FilesApp> {
		FilesApp(AForkableCursor<Index<Keyword,ACell>> cursor) {
			super(cursor);
			create(Keywords.APP,(AForkableCursor<Index<Keyword,ACell>> c) -> new AppData(c));
			create(Keywords.FS,(AForkableCursor<AHashMap<ACell,SignedData<AHashMap<AString,AVector<ACell>>>>> c) ->
				DLFSRegion.connect(c,null,OWNER::signData,() -> CVMLong.create(TIME)));
		}
		private FilesApp(AForkableCursor<Index<Keyword,ACell>> cursor,FilesApp source) { super(cursor,source); }
		@Override public FilesApp rebind(AForkableCursor<Index<Keyword,ACell>> cursor) { return new FilesApp(cursor,this); }

		Counter filesWritten() {
			AppData app=registered(Keywords.APP);
			return app.filesWritten();
		}

		/** The owner's drive, made on its first use. */
		DLFSDrive drive() {
			DLFSRegion files=registered(Keywords.FS);
			return files.drives(OWNER.getAccountKey()).getOrCreateDrive("home");
		}

		/** Writes a file and counts it. */
		void write(String path,String text) {
			try {
				Files.writeString(drive().fileSystem().getPath(path),text);
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
			filesWritten().increment();
		}
	}

	private static FilesApp open() {
		return new FilesApp(new Root<>(Index.<Keyword,ACell>none()));
	}

	private static String read(FilesApp app,String path) throws IOException {
		return Files.readString(app.drive().fileSystem().getPath(path));
	}

	@Test void aFileAndItsCountCommitInOneTransaction() throws IOException {
		var app=open();
		app.execute(a -> a.write("/a.txt","first"));
		app.execute(a -> {
			a.write("/b.txt","second");
			a.write("/c.txt","third");
		});
		assertEquals(3,app.filesWritten().count());
		assertEquals("second",read(app,"/b.txt"));
	}

	@Test void aFailedTransactionLeavesNeitherFileNorCount() {
		var app=open();
		app.execute(a -> a.write("/a.txt","first"));
		var before=app.value();
		assertThrows(IllegalStateException.class,() -> app.execute(a -> {
			a.write("/b.txt","second");
			throw new IllegalStateException("a later step fails");
		}));
		assertSame(before,app.value());
		assertEquals(1,app.filesWritten().count());
		assertThrows(NoSuchFileException.class,() -> read(app,"/b.txt"));
	}
}
