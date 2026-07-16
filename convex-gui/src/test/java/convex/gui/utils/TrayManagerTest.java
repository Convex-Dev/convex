package convex.gui.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.awt.Image;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TrayManagerTest {

	@AfterEach
	void removeTray() {
		TrayManager.remove();
	}

	@Test
	void headlessInstallIsANoop() {
		AtomicBoolean opened=new AtomicBoolean();
		AtomicBoolean closed=new AtomicBoolean();
		assertFalse(TrayManager.install("Test","Open",()->opened.set(true),()->closed.set(true),true));
		assertFalse(TrayManager.isInstalled());
		assertFalse(opened.get());
		assertFalse(closed.get());
	}

	@Test
	void validatesActionPairsBeforeDesktopAccess() {
		assertThrows(IllegalArgumentException.class,
			()->TrayManager.install("Test","Open",null,()->{},true));
		assertThrows(IllegalArgumentException.class,
			()->TrayManager.install("Test",null,()->{},()->{},true));
		assertThrows(IllegalArgumentException.class,
			()->TrayManager.install("Test",null,null,null,true));
	}

	@Test
	void clampsPlatformTooltipLimit() {
		assertEquals("Convex",TrayManager.clampTooltip(null));
		String value="x".repeat(200);
		String clamped=TrayManager.clampTooltip(value);
		assertEquals(127,clamped.length());
		assertEquals('…',clamped.charAt(126));
	}

	@Test
	void loadsTrayImage() {
		Image image=TrayManager.loadIcon();
		assertNotNull(image);
		assertFalse(image.getWidth(null)<=0);
		assertFalse(image.getHeight(null)<=0);
	}
}
