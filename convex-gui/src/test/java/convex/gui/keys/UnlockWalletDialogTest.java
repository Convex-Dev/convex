package convex.gui.keys;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.awt.EventQueue;
import java.awt.GraphicsEnvironment;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.crypto.wallet.HotWalletEntry;

/** Closing the unlock dialog releases its window. The dialog is never shown. */
public class UnlockWalletDialogTest {

	@Test
	public void closingReleasesTheWindow() throws Exception {
		assumeFalse(GraphicsEnvironment.isHeadless(),"a dialog needs a display");
		HotWalletEntry entry=HotWalletEntry.create(AKeyPair.generate(),"test");
		EventQueue.invokeAndWait(()->{
			UnlockWalletDialog dialog=new UnlockWalletDialog(entry);
			try {
				assertTrue(dialog.isDisplayable());
				dialog.close();
				assertFalse(dialog.isDisplayable(),"the closed dialog still holds its window");
			} finally {
				dialog.dispose();
			}
		});
	}
}
