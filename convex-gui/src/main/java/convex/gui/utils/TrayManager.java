package convex.gui.utils;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.imageio.ImageIO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Process-level system tray support with caller-supplied open and close actions.
 *
 * <p>Installation is strictly best-effort. Headless JVMs, unsupported desktops,
 * failures in native AWT integration, and {@code CONVEX_NO_TRAY=1} all leave the
 * process running normally without a tray icon. At most one icon is installed
 * per JVM.
 */
public final class TrayManager {

	private static final Logger log=LoggerFactory.getLogger(TrayManager.class);
	private static final int TOOLTIP_MAX=127;
	private static final AtomicReference<TrayIcon> ICON=new AtomicReference<>();
	private static final AtomicBoolean INSTALLING=new AtomicBoolean();

	private TrayManager() {
	}

	/**
	 * Installs the Convex tray icon.
	 *
	 * @param tooltip hover text
	 * @param openLabel label for the open action, or null for no open action
	 * @param onOpen action invoked by the open item and tray activation
	 * @param onClose graceful process close action
	 * @return true if this call installed the tray icon
	 */
	public static boolean install(String tooltip, String openLabel, Runnable onOpen, Runnable onClose) {
		return install(tooltip,openLabel,onOpen,onClose,GraphicsEnvironment.isHeadless());
	}

	/** Test seam which avoids touching the real desktop when {@code headless} is true. */
	static boolean install(String tooltip, String openLabel, Runnable onOpen, Runnable onClose, boolean headless) {
		if (ICON.get()!=null) return false;
		if (onClose==null) throw new IllegalArgumentException("Tray close action cannot be null");
		if ((openLabel==null)!=(onOpen==null)) {
			throw new IllegalArgumentException("Tray open label and action must both be present or absent");
		}
		if (headless||"1".equals(System.getenv("CONVEX_NO_TRAY"))) return false;
		if (!INSTALLING.compareAndSet(false,true)) return false;

		try {
			if (!SystemTray.isSupported()) return false;
			AtomicBoolean installed=new AtomicBoolean();
			Runnable task=()->installed.set(installOnEventThread(tooltip,openLabel,onOpen,onClose));
			if (EventQueue.isDispatchThread()) {
				task.run();
			} else {
				EventQueue.invokeAndWait(task);
			}
			if (installed.get()) log.info("System tray icon installed ({})",tooltip);
			return installed.get();
		} catch (Throwable t) {
			log.warn("System tray unavailable: {}",t.toString());
			return false;
		} finally {
			INSTALLING.set(false);
		}
	}

	/** Builds and adds the icon. Must run on the AWT event thread. */
	private static boolean installOnEventThread(String tooltip, String openLabel, Runnable onOpen, Runnable onClose) {
		if (ICON.get()!=null) return true;
		try {
			PopupMenu menu=new PopupMenu();
			if (onOpen!=null) {
				MenuItem open=new MenuItem(openLabel);
				open.addActionListener(e->runAsync("convex-tray-open",onOpen));
				menu.add(open);
				menu.addSeparator();
			}

			MenuItem close=new MenuItem("Close");
			close.addActionListener(e->runAsync("convex-tray-close",()->{
				onClose.run();
				remove();
			}));
			menu.add(close);

			SystemTray tray=SystemTray.getSystemTray();
			Image image=loadIcon();
			Dimension size=tray.getTrayIconSize();
			TrayIcon icon;
			if ((size!=null)&&(size.width>0)&&(size.height>0)) {
				image=image.getScaledInstance(size.width,size.height,Image.SCALE_SMOOTH);
				icon=new TrayIcon(image,clampTooltip(tooltip),menu);
			} else {
				icon=new TrayIcon(image,clampTooltip(tooltip),menu);
				icon.setImageAutoSize(true);
			}
			if (onOpen!=null) icon.addActionListener(e->runAsync("convex-tray-open",onOpen));
			tray.add(icon);
			ICON.set(icon);
			return true;
		} catch (Exception e) {
			log.warn("Could not install system tray icon: {}",e.toString());
			return false;
		}
	}

	/** Removes the process tray icon, if installed. Safe to call repeatedly. */
	public static void remove() {
		TrayIcon icon=ICON.getAndSet(null);
		if (icon==null) return;
		Runnable task=()->{
			try {
				SystemTray.getSystemTray().remove(icon);
			} catch (Throwable t) {
				log.debug("Unable to remove system tray icon",t);
			}
		};
		if (EventQueue.isDispatchThread()) {
			task.run();
		} else {
			EventQueue.invokeLater(task);
		}
	}

	/** @return true if this JVM currently owns a Convex tray icon */
	public static boolean isInstalled() {
		return ICON.get()!=null;
	}

	static String clampTooltip(String tooltip) {
		String value=(tooltip==null)?"Convex":tooltip;
		return (value.length()<=TOOLTIP_MAX)?value:value.substring(0,TOOLTIP_MAX-1)+"…";
	}

	static Image loadIcon() {
		try (InputStream in=TrayManager.class.getResourceAsStream("/images/Convex.png")) {
			if (in!=null) {
				Image image=ImageIO.read(in);
				if (image!=null) return image;
			}
		} catch (Exception e) {
			log.warn("Convex tray icon resource unreadable: {}",e.toString());
		}

		BufferedImage image=new BufferedImage(64,64,BufferedImage.TYPE_INT_ARGB);
		Graphics2D g=image.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(new Color(0x35,0x8c,0xb4));
		g.fillOval(2,2,60,60);
		g.setColor(Color.WHITE);
		g.fillOval(18,18,28,28);
		g.setColor(new Color(0x35,0x8c,0xb4));
		g.fillOval(25,18,28,28);
		g.dispose();
		return image;
	}

	private static void runAsync(String name, Runnable action) {
		Thread.ofVirtual().name(name).start(()->{
			try {
				action.run();
			} catch (Throwable t) {
				log.warn("System tray action failed: {}",t.toString());
			}
		});
	}
}
