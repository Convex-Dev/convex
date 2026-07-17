package convex.cli;

import java.awt.EventQueue;

import convex.api.ConvexLocal;
import convex.gui.server.PeerWindow;
import convex.gui.utils.Toolkit;
import convex.gui.utils.TrayManager;
import convex.peer.Server;
import convex.restapi.RESTServer;

/** Concrete tray actions shared by long-running Convex CLI commands. */
public final class TraySupport {

	private TraySupport() {
	}

	/**
	 * Installs a tray icon for a Peer-backed service. REST-enabled services open
	 * the local web explorer; otherwise the action opens a local Peer GUI.
	 *
	 * @param tooltip tray hover text
	 * @param server local Peer server
	 * @param restServer REST server, or null when disabled
	 * @param disabled true to suppress tray installation
	 * @param onClose graceful shutdown action
	 * @return true if a tray icon is installed
	 */
	public static boolean installPeerTray(String tooltip, Server server, RESTServer restServer,
		boolean disabled, Runnable onClose) {
		if (disabled) return false;
		if (restServer!=null) {
			String url="http://localhost:"+restServer.getPort()+"/explorer/";
			return TrayManager.install(tooltip,"Open Web",()->Toolkit.launchBrowser(url),onClose);
		}
		return TrayManager.install(tooltip,"Open GUI",()->openPeerGUI(server),onClose);
	}

	/** Opens a control window for an already-running local Peer. */
	public static void openPeerGUI(Server server) {
		EventQueue.invokeLater(()->{
			ConvexLocal convex=ConvexLocal.connect(server);
			PeerWindow window=new PeerWindow(convex);
			window.runNonModal(null);
		});
	}
}
