package convex.gui.dlfs;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.awt.GraphicsEnvironment;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;

import convex.core.data.Blobs;
import convex.lattice.fs.DLFS;
import convex.lattice.fs.DLFSProvider;
import convex.lattice.fs.DLFileSystem;

public class DLFSGUITest {

	@Test public void testDLFSPanel() throws Exception {
		assumeFalse(GraphicsEnvironment.isHeadless());

		DLFSProvider provider=DLFS.provider();
		try (DLFileSystem fs=provider.newFileSystem(new URI("dlfs-test"),null)) {
			DLFSPanel pan=new DLFSPanel(fs);
			try {
				assertSame(fs,pan.getFileSystem());
			} finally {
				pan.close();
			}
		}

	}

	/** A store that cannot be opened leaves the browser serving its previous store (#777). */
	@Test public void testFailedStoreSwitchRestoresPreviousStore() throws Exception {
		assumeFalse(GraphicsEnvironment.isHeadless());

		File good=File.createTempFile("dlfs-guitest-",".db");
		good.deleteOnExit();
		File bad=File.createTempFile("dlfs-guitest-bad-",".db");
		bad.deleteOnExit();
		Files.write(bad.toPath(),Blobs.createRandom(10000).getBytes());

		DLFSBrowser browser=new DLFSBrowser(good);
		try {
			AtomicReference<Throwable> failure=new AtomicReference<>();
			SwingUtilities.invokeAndWait(() -> {
				try {
					browser.switchStore(bad);
				} catch (Throwable e) {
					failure.set(e);
				}
			});
			assertInstanceOf(IOException.class,failure.get());
			assertNotNull(browser.getDrive("demo"),"the previous store's drives are served again");
			assertNotNull(browser.getWebDAVUrl(),"WebDAV is restarted after a failed switch");
		} finally {
			browser.close();
		}
	}

}
