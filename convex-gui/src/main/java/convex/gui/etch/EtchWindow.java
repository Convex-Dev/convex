package convex.gui.etch;

import javax.swing.JFrame;
import javax.swing.JTabbedPane;

import convex.api.Convex;
import convex.api.ConvexLocal;
import convex.etch.EtchStore;
import convex.gui.components.AbstractGUI;
import convex.gui.peer.PeerComponent;
import net.miginfocom.swing.MigLayout;

@SuppressWarnings("serial")
public class EtchWindow extends AbstractGUI {
	EtchStore store;
	Convex peer;
	
	public EtchStore getEtchStore() {
		return store;
	}
	
	JTabbedPane tabbedPane = new JTabbedPane(JTabbedPane.TOP);

	public EtchWindow(ConvexLocal peer) {
		super ("Etch Storage View - "+peer.getLocalServer().getStore());
		this.store=(EtchStore) peer.getLocalServer().getStore();
		setLayout(new MigLayout());
		
		PeerComponent pcom=new PeerComponent(peer);
		add(pcom, "dock north");
		
		add(tabbedPane, "dock center");
	}

	@Override
	public void setupFrame(JFrame frame) {
		frame.getContentPane().setLayout(new MigLayout());
		frame.getContentPane().add(this,"dock center");
	}

}
