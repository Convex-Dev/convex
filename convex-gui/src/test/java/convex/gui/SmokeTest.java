package convex.gui;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.data.AArrayBlob;
import convex.core.data.ACell;
import convex.core.data.Blobs;
import convex.core.data.Hash;
import convex.core.data.prim.AInteger;
import convex.core.data.prim.CVMLong;
import convex.gui.components.DecimalAmountField;
import convex.gui.components.Identicon;
import convex.gui.models.ComboModel;
import convex.gui.models.StateModel;
import convex.gui.utils.RobinsonProjection;
import convex.gui.wallet.TokenInfo;
import convex.lattice.fs.DLFileSystem;
import convex.lattice.fs.DLPath;

/**
 * Smoke tests for convex-gui infrastructure classes.
 * These test pure logic and data models — no GUI/Swing interactions required.
 */
public class SmokeTest {

	// ========== StateModel ==========

	@Test
	public void testStateModelCreate() {
		StateModel<String> model = StateModel.create("hello");
		assertEquals("hello", model.getValue());
	}

	@Test
	public void testStateModelNull() {
		StateModel<String> model = new StateModel<>();
		assertNull(model.getValue());
	}

	@Test
	public void testStateModelSetValue() {
		StateModel<Integer> model = StateModel.create(1);
		assertEquals(1, model.getValue());
		model.setValue(42);
		assertEquals(42, model.getValue());
	}

	@Test
	public void testStateModelSetNull() {
		StateModel<String> model = StateModel.create("initial");
		model.setValue(null);
		assertNull(model.getValue());
	}

	// ========== ComboModel ==========

	@Test
	public void testComboModelEmpty() {
		ComboModel<String> model = new ComboModel<>();
		assertEquals(0, model.getSize());
		assertFalse(model.contains("anything"));
	}

	@Test
	public void testComboModelEnsureContains() {
		ComboModel<String> model = new ComboModel<>();
		model.ensureContains("alpha");
		assertEquals(1, model.getSize());
		assertTrue(model.contains("alpha"));

		// Ensure idempotent
		model.ensureContains("alpha");
		assertEquals(1, model.getSize());
	}

	@Test
	public void testComboModelMultipleItems() {
		ComboModel<Integer> model = new ComboModel<>();
		model.ensureContains(1);
		model.ensureContains(2);
		model.ensureContains(3);
		assertEquals(3, model.getSize());
		assertTrue(model.contains(2));
		assertFalse(model.contains(99));
	}

	// ========== TokenInfo ==========

	@Test
	public void testConvexCoin() {
		TokenInfo coin = TokenInfo.convexCoin();
		assertNotNull(coin);
		assertTrue(coin.isConvex());
		assertNull(coin.getID());
		assertEquals("CVM", coin.getSymbol());
		assertEquals(9, coin.decimals());
		assertEquals(9, coin.getDecimals());
	}

	@Test
	public void testTokenInfoForID() {
		ACell tokenID = CVMLong.create(42);
		TokenInfo token = TokenInfo.forID(tokenID);
		assertNotNull(token);
		assertFalse(token.isConvex());
		assertEquals(tokenID, token.getID());
		assertEquals(2, token.decimals());
	}

	@Test
	public void testTokenInfoEquals() {
		TokenInfo coin1 = TokenInfo.convexCoin();
		TokenInfo coin2 = TokenInfo.convexCoin();
		assertEquals(coin1, coin2);

		TokenInfo t1 = TokenInfo.forID(CVMLong.create(100));
		TokenInfo t2 = TokenInfo.forID(CVMLong.create(100));
		assertEquals(t1, t2);

		assertNotEquals(coin1, t1);
	}

	@Test
	public void testTokenInfoDecimalAmount() {
		TokenInfo coin = TokenInfo.convexCoin();
		// 1 CVM = 1,000,000,000 copper
		BigDecimal amount = coin.decimalAmount(CVMLong.create(1_000_000_000L));
		assertEquals(0, BigDecimal.ONE.compareTo(amount));

		// 0.5 CVM
		BigDecimal half = coin.decimalAmount(CVMLong.create(500_000_000L));
		assertEquals(0, new BigDecimal("0.5").compareTo(half));
	}

	// ========== DecimalAmountField.parse ==========

	@Test
	public void testParseDecimal9() {
		// 9 decimal places (Convex coin)
		AInteger result = DecimalAmountField.parse("1.5", 9, false);
		assertNotNull(result);
		assertEquals(1_500_000_000L, result.longValue());
	}

	@Test
	public void testParseWholeNumber() {
		AInteger result = DecimalAmountField.parse("42", 9, false);
		assertNotNull(result);
		assertEquals(42_000_000_000L, result.longValue());
	}

	@Test
	public void testParseZero() {
		AInteger result = DecimalAmountField.parse("0", 9, false);
		assertNotNull(result);
		assertEquals(0L, result.longValue());
	}

	@Test
	public void testParseNoDecimals() {
		AInteger result = DecimalAmountField.parse("1234", 0, false);
		assertNotNull(result);
		assertEquals(1234L, result.longValue());
	}

	@Test
	public void testParseWithDecimals0() {
		// Truncation when decimals=0
		AInteger result = DecimalAmountField.parse("3.444", 0, false);
		assertNotNull(result);
		assertEquals(3L, result.longValue());
	}

	@Test
	public void testParseTrimWhitespace() {
		AInteger result = DecimalAmountField.parse("   100   ", 2, false);
		assertNotNull(result);
		assertEquals(10000L, result.longValue());
	}

	@Test
	public void testParseInvalid() {
		assertNull(DecimalAmountField.parse("not-a-number", 9, false));
		assertNull(DecimalAmountField.parse("", 9, false));
	}

	@Test
	public void testParseDot() {
		assertNull(DecimalAmountField.parse(".", 0, false));
	}

	@Test
	public void testParseTrailingDot() {
		AInteger result = DecimalAmountField.parse("13.", 0, false);
		assertNotNull(result);
		assertEquals(13L, result.longValue());
	}

	@Test
	public void testParseExact() {
		// Exact mode should reject fractional results
		AInteger result = DecimalAmountField.parse("1.5", 0, true);
		assertNull(result);

		// Exact mode with matching decimals
		AInteger exact = DecimalAmountField.parse("1.50", 2, true);
		assertNotNull(exact);
		assertEquals(150L, exact.longValue());
	}

	// ========== RobinsonProjection ==========

	@Test
	public void testProjectionOrigin() {
		Point2D pt = RobinsonProjection.getPoint(0, 0);
		assertNotNull(pt);
		// Origin should map to approximate centre
		assertEquals(0.45, pt.getX(), 0.01);
		assertEquals(0.5, pt.getY(), 0.01);
	}

	@Test
	public void testProjectionNorthPole() {
		Point2D pt = RobinsonProjection.getPoint(90, 0);
		assertNotNull(pt);
		// North pole should be near top (y close to 0)
		assertTrue(pt.getY() < 0.2, "North pole Y should be near top: " + pt.getY());
	}

	@Test
	public void testProjectionSouthPole() {
		Point2D pt = RobinsonProjection.getPoint(-90, 0);
		assertNotNull(pt);
		// South pole should be near bottom (y close to 1)
		assertTrue(pt.getY() > 0.8, "South pole Y should be near bottom: " + pt.getY());
	}

	@Test
	public void testProjectionSymmetry() {
		Point2D north = RobinsonProjection.getPoint(45, 0);
		Point2D south = RobinsonProjection.getPoint(-45, 0);
		// Should be symmetric around y=0.5
		assertEquals(0.5, (north.getY() + south.getY()) / 2, 0.01);
	}

	@Test
	public void testProjectionLongitude() {
		Point2D west = RobinsonProjection.getPoint(0, -90);
		Point2D east = RobinsonProjection.getPoint(0, 90);
		// Equal longitude should be equidistant from centre
		assertEquals(0.45, (west.getX() + east.getX()) / 2, 0.01);
	}

	// ========== Identicon image generation ==========

	@Test
	public void testIdenticonCreateImage() {
		Hash h = Hash.get(CVMLong.create(42));
		BufferedImage img = Identicon.createImage(h, 32);
		assertNotNull(img);
		assertEquals(32, img.getWidth());
		assertEquals(32, img.getHeight());
	}

	@Test
	public void testIdenticonDifferentHashes() {
		Hash h1 = Hash.get(CVMLong.create(1));
		Hash h2 = Hash.get(CVMLong.create(2));
		BufferedImage img1 = Identicon.createImage(h1, 16);
		BufferedImage img2 = Identicon.createImage(h2, 16);
		assertNotNull(img1);
		assertNotNull(img2);
		// Different hashes should produce different images (check a few pixels)
		boolean anyDifferent = false;
		for (int x = 0; x < 16; x++) {
			for (int y = 0; y < 16; y++) {
				if (img1.getRGB(x, y) != img2.getRGB(x, y)) {
					anyDifferent = true;
					break;
				}
			}
			if (anyDifferent) break;
		}
		assertTrue(anyDifferent, "Different hashes should produce different identicons");
	}

	@Test
	public void testIdenticonNullData() {
		BufferedImage img = Identicon.createImage(null, 16);
		assertNotNull(img);
		assertEquals(16, img.getWidth());
	}

	@Test
	public void testIdenticonEmptyData() {
		AArrayBlob empty = Blobs.empty().toFlatBlob();
		BufferedImage img = Identicon.createImage(empty, 16);
		assertNotNull(img);
	}

	@Test
	public void testIdenticonDeterministic() {
		Hash h = Hash.get(CVMLong.create(999));
		BufferedImage img1 = Identicon.createImage(h, 32);
		BufferedImage img2 = Identicon.createImage(h, 32);
		// Same hash should produce identical images
		for (int x = 0; x < 32; x++) {
			for (int y = 0; y < 32; y++) {
				assertEquals(img1.getRGB(x, y), img2.getRGB(x, y),
					"Pixel mismatch at (" + x + "," + y + ")");
			}
		}
	}

	// ========== DLFSBrowser.createDemoDrive ==========

	@Test
	public void testCreateDemoDrive() throws IOException {
		DLFileSystem drive = convex.gui.dlfs.DLFSBrowser.createDemoDrive();
		assertNotNull(drive);
		assertTrue(drive.isOpen());

		DLPath root = drive.getRoot();
		assertNotNull(root);
		assertEquals(0, root.getNameCount());

		// Check that expected directories were created
		assertTrue(Files.isDirectory(root.resolve("training")));
		assertTrue(Files.isDirectory(root.resolve("models")));
		assertTrue(Files.isDirectory(root.resolve("input")));
		assertTrue(Files.isDirectory(root.resolve("provenance")));
		assertTrue(Files.isDirectory(root.resolve("pytools")));
		assertTrue(Files.isDirectory(root.resolve("cuda")));

		// Check that expected files exist
		assertTrue(Files.exists(root.resolve("models/ace1.tensor")));
		assertTrue(Files.exists(root.resolve("models/ace2.tensor")));

		// Check subdirectories
		assertTrue(Files.isDirectory(root.resolve("models/old")));
		assertTrue(Files.isDirectory(root.resolve("models/experimental")));

		// Tombstone should have been deleted
		assertFalse(Files.exists(root.resolve("tombstone")));

		// Root hash should be non-null
		assertNotNull(drive.getRootHash());
	}

	// ========== DLFSBrowser cursor-backed drive management ==========

	/** Creates a DLFSBrowser backed by a temporary Etch store */
	private static convex.gui.dlfs.DLFSBrowser createTempBrowser() throws IOException {
		File tempDb = File.createTempFile("dlfs-test-", ".db");
		tempDb.deleteOnExit();
		return new convex.gui.dlfs.DLFSBrowser(tempDb);
	}

	@Test
	public void testCursorBackedDriveCreation() throws IOException {
		convex.gui.dlfs.DLFSBrowser browser = createTempBrowser();

		// Demo drive should exist from constructor
		List<String> driveNames = browser.listDrives();
		assertTrue(driveNames.contains("demo"), "Demo drive should exist");

		DLFileSystem demo = browser.getDrive("demo");
		assertNotNull(demo);
		assertTrue(demo.isOpen());
		assertTrue(Files.isDirectory(demo.getRoot().resolve("models")));
	}

	@Test
	public void testCreateAndDeleteDrive() throws IOException {
		convex.gui.dlfs.DLFSBrowser browser = createTempBrowser();

		assertTrue(browser.createDrive("test1"));
		assertFalse(browser.createDrive("test1"), "Duplicate create should fail");

		DLFileSystem drive = browser.getDrive("test1");
		assertNotNull(drive);
		assertTrue(drive.isOpen());

		assertTrue(browser.deleteDrive("test1"));
		assertNull(browser.getDrive("test1"));
		assertFalse(browser.deleteDrive("test1"), "Double delete should fail");
	}

	@Test
	public void testDriveIsolation() throws IOException {
		convex.gui.dlfs.DLFSBrowser browser = createTempBrowser();

		browser.createDrive("a");
		browser.createDrive("b");

		DLFileSystem driveA = browser.getDrive("a");
		DLFileSystem driveB = browser.getDrive("b");

		// Write to drive A
		Files.createDirectory(driveA.getRoot().resolve("only-in-a"));

		// Drive B should not have it
		assertFalse(Files.exists(driveB.getRoot().resolve("only-in-a")));
	}

	// ========== DecimalAmountField.getNumberFormat ==========

	@Test
	public void testGetNumberFormat() {
		java.text.DecimalFormat df = DecimalAmountField.getNumberFormat(2);
		assertNotNull(df);
		assertEquals("1,234.56", df.format(1234.56));
	}

	@Test
	public void testGetNumberFormatNoDecimals() {
		java.text.DecimalFormat df = DecimalAmountField.getNumberFormat(0);
		assertNotNull(df);
		assertEquals("1,234", df.format(1234));
	}

	// ========== Toolkit utility (non-GUI) ==========

	@Test
	public void testSmoothResize() {
		BufferedImage src = new BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB);
		BufferedImage result = convex.gui.utils.Toolkit.smoothResize(src, 50, 50);
		assertNotNull(result);
		assertEquals(50, result.getWidth());
		assertEquals(50, result.getHeight());
	}

	@Test
	public void testPixelResize() {
		BufferedImage src = new BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB);
		BufferedImage result = convex.gui.utils.Toolkit.pixelResize(src, 20, 20);
		assertNotNull(result);
		assertEquals(20, result.getWidth());
		assertEquals(20, result.getHeight());
	}

	@Test
	public void testCAD3Filter() {
		javax.swing.filechooser.FileFilter filter = convex.gui.utils.Toolkit.getCAD3Filter();
		assertNotNull(filter);
		assertTrue(filter.accept(new File("test.cad3")));
		assertTrue(filter.accept(new File("test.cad")));
		assertFalse(filter.accept(new File("test.txt")));
		assertTrue(filter.accept(new File("."))); // directories accepted
		assertEquals("CAD3 file (*.cad3, *.cad)", filter.getDescription());
	}
}
