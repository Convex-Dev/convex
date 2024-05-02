package convex.gui.peer;

import java.awt.BorderLayout;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.api.Convex;
import convex.api.ConvexLocal;
import convex.api.ConvexRemote;
import convex.core.Coin;
import convex.core.exceptions.TODOException;
import convex.core.util.Utils;
import convex.gui.components.ActionButton;
import convex.gui.components.ActionPanel;
import convex.gui.components.ScrollyList;

@SuppressWarnings({ "serial", "unused" })
public class PeersListPanel extends JPanel {

	JPanel peersPanel;

	JPanel peerViewPanel;
	JScrollPane scrollPane;

	PeerGUI manager;

	static final Logger log = LoggerFactory.getLogger(PeersListPanel.class.getName());

	/**
	 * Gets a list of all locally operating Servers from the current peer list.
	 *
	 * @return List of local PeerView objects
	 */
	public List<ConvexLocal> getPeerViews() {
		DefaultListModel<ConvexLocal> peerList = manager.getPeerList();
		ArrayList<ConvexLocal> al = new ArrayList<>();
		int n = peerList.getSize();
		for (int i = 0; i < n; i++) {
			ConvexLocal p = peerList.getElementAt(i);
			al.add(p);
		}
		return al;
	}

	/**
	 * Create the panel.
	 * @param manager PeerGUI instance
	 */
	public PeersListPanel(PeerGUI manager) {
		this.manager=manager;
		setLayout(new BorderLayout(0, 0));

		JPanel toolBar = new ActionPanel();
		add(toolBar, BorderLayout.SOUTH);

		ActionButton btnLaunch = new ActionButton("Launch!",0xeb9b,e -> manager.launchExtraPeer());
		btnLaunch.setToolTipText("Launch an extra peer for the network. Allocates some stake from genesis account");
		toolBar.add(btnLaunch);

		JButton btnConnect = new JButton("Connect...");
		toolBar.add(btnConnect);
		btnConnect.addActionListener(e -> {
			String input = JOptionPane.showInputDialog("Enter host address: ", "localhost:18888");
			if (input==null) return; // no result?

			InetSocketAddress hostAddress = Utils.toInetSocketAddress(input);
			ConvexRemote pc;
			try {
				// TODO: we want to receive anything?
				pc = Convex.connect(hostAddress, null,null);
				throw new TODOException();
				//addPeer(pc);
			} catch (Throwable e1) {
				JOptionPane.showMessageDialog(this, "Connect failed: " + e1.toString());
			}

		});

		ScrollyList<ConvexLocal> scrollyList = new ScrollyList<>(manager.getPeerList(),
				peer -> new PeerComponent(peer));
		add(scrollyList, BorderLayout.CENTER);
	}

}
