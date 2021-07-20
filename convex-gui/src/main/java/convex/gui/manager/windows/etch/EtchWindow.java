package convex.gui.manager.windows.etch;

import java.awt.BorderLayout;

import javax.swing.JTabbedPane;

import convex.gui.components.PeerComponent;
import convex.gui.components.PeerView;
import convex.gui.manager.PeerGUI;
import convex.gui.manager.windows.BaseWindow;
import etch.EtchStore;

@SuppressWarnings("serial")
public class EtchWindow extends BaseWindow {
	EtchStore store;
	PeerView peer;
	

	
	public EtchStore getEtchStore() {
		return store;
	}
	
	JTabbedPane tabbedPane = new JTabbedPane(JTabbedPane.TOP);

	public EtchWindow(PeerGUI manager, PeerView peer) {
		super(manager);
		this.peer=peer;
		this.store=(EtchStore) peer.peerServer.getStore();
		
		PeerComponent pcom=new PeerComponent(manager,peer);
		add(pcom, BorderLayout.NORTH);
		
		add(tabbedPane, BorderLayout.CENTER);
	}

	@Override
	public String getTitle() {
		try {
			 return "Storage view - "+peer.getHostAddress();
		}
		catch (Exception e) {
			return "Storage view - Unknown";
		}
	}

}
