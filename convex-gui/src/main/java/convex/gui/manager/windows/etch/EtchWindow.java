package convex.gui.manager.windows.etch;

import java.awt.BorderLayout;

import javax.swing.JTabbedPane;

import convex.api.Convex;
import convex.gui.PeerGUI;
import convex.gui.components.PeerComponent;
import convex.gui.manager.windows.BaseWindow;
import etch.EtchStore;

@SuppressWarnings("serial")
public class EtchWindow extends BaseWindow {
	EtchStore store;
	Convex peer;
	

	
	public EtchStore getEtchStore() {
		return store;
	}
	
	JTabbedPane tabbedPane = new JTabbedPane(JTabbedPane.TOP);

	public EtchWindow(PeerGUI manager, Convex peer) {
		super(manager);
		this.peer=peer;
		this.store=(EtchStore) peer.getLocalServer().getStore();
		
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
