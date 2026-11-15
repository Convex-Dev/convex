package convex.lattice;

import static convex.lattice.app.ShopExample.*;
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
import convex.core.data.Maps;
import convex.core.data.SignedData;
import convex.core.data.prim.CVMLong;
import convex.dlfs.DLFSDrive;
import convex.dlfs.DLFSRegion;
import convex.lattice.app.ShopExample;
import convex.lattice.app.ShopExample.AppRegion;
import convex.lattice.app.ShopExample.Shop;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.Root;

/**
 * The {@link ShopExample}'s shop beside files: a root application serving the shop
 * in its {@code :app} region and DLFS drives at {@code :fs}, so that one
 * transaction on the root commits a file with a shop update.
 */
class ShopWithFilesTest {

	static final AKeyPair OWNER=AKeyPair.createSeeded(7401);

	/** Logical time for file writes, which keeps values deterministic. */
	static final long TIME=1000;

	/** The root application: the shop at {@code [:app :shop]} and files at {@code :fs}. */
	static final class ShopAndFiles extends Regions implements TransactionalComponent<Index<Keyword,ACell>,ShopAndFiles> {
		ShopAndFiles(AForkableCursor<Index<Keyword,ACell>> cursor) {
			super(cursor);
			create(Keywords.APP,(AForkableCursor<Index<Keyword,ACell>> c) -> new AppRegion(c));
			create(Keywords.FS,(AForkableCursor<AHashMap<ACell,SignedData<AHashMap<AString,AVector<ACell>>>>> c) ->
				DLFSRegion.connect(c,null,OWNER::signData,() -> CVMLong.create(TIME)));
		}
		private ShopAndFiles(AForkableCursor<Index<Keyword,ACell>> cursor,ShopAndFiles source) { super(cursor,source); }
		@Override public ShopAndFiles rebind(AForkableCursor<Index<Keyword,ACell>> cursor) { return new ShopAndFiles(cursor,this); }

		Shop shop() { return this.<AppRegion>registered(Keywords.APP).shop(); }

		/** The owner's receipts drive, made on its first use. */
		DLFSDrive receipts() {
			DLFSRegion files=registered(Keywords.FS);
			return files.drives(OWNER.getAccountKey()).getOrCreateDrive("receipts");
		}
	}

	private static ShopAndFiles open() {
		return new ShopAndFiles(new Root<>(Index.<Keyword,ACell>none()));
	}

	private static void write(DLFSDrive drive,String path,String text) {
		try {
			Files.writeString(drive.fileSystem().getPath(path),text);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static String read(DLFSDrive drive,String path) throws IOException {
		return Files.readString(drive.fileSystem().getPath(path));
	}

	@Test void aFileCommitsInTheSameTransactionAsAnOrder() throws IOException {
		var app=open();
		app.shop().list(TEA,1);
		app.execute(a -> {
			a.shop().order(1,TEA);
			write(a.receipts(),"/1.txt","one tea");
		});
		assertEquals(0,app.shop().stock().count(TEA));
		assertEquals(Maps.of(CVMLong.ONE,TEA),app.shop().value().get(ORDERS));
		assertEquals("one tea",read(app.receipts(),"/1.txt"));
	}

	@Test void anOrderThatFailsLeavesNoFile() throws IOException {
		var app=open();
		app.shop().list(TEA,1);
		app.shop().order(1,TEA);
		write(app.receipts(),"/1.txt","one tea");
		var before=app.value();
		assertThrows(IllegalStateException.class,() -> app.execute(a -> {
			write(a.receipts(),"/2.txt","another tea");
			a.shop().order(2,TEA);
		}),"out of stock");
		assertSame(before,app.value(),"neither the file nor the order");
		assertThrows(NoSuchFileException.class,() -> read(app.receipts(),"/2.txt"));
	}

	@Test void aFileAndOrderCommittedAroundAnExclusiveShopTransactionAreMergedIntoIt() throws IOException {
		var app=open();
		var shop=app.shop();
		shop.list(TEA,2);
		shop.exclusive().execute(s -> {
			// The root commits at the root, not through the shop's claimed boundary
			app.execute(a -> {
				a.shop().order(1,TEA);
				write(a.receipts(),"/1.txt","one tea");
			});
			s.stock().restock(TEA,10);
		});
		assertEquals(11,shop.stock().count(TEA),"two, less the order, plus the restock");
		assertEquals(Maps.of(CVMLong.ONE,TEA),shop.value().get(ORDERS));
		assertEquals("one tea",read(app.receipts(),"/1.txt"),"the file is untouched by the shop's commit");
	}
}
