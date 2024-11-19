package convex.gui.peer.stake;

import java.awt.BorderLayout;

import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;

import convex.api.Convex;
import convex.core.data.AccountKey;
import convex.core.data.prim.AInteger;
import convex.gui.components.ActionButton;
import convex.gui.components.ActionPanel;
import convex.gui.components.DecimalAmountField;
import convex.gui.utils.SymbolIcon;

@SuppressWarnings("serial")
public class PeerStakePanel extends JPanel {

	protected Convex convex;

	public PeerStakePanel(Convex convex) {
		super (new BorderLayout());
		this.convex=convex;
		
		PeerStakeTable peerTable=new PeerStakeTable(convex); 
		add(new JScrollPane(peerTable),BorderLayout.CENTER);
		
		ActionPanel ap=new ActionPanel();
		add(ap,BorderLayout.SOUTH);
		
		ap.add(new ActionButton("Refresh", 0xe5d5, e->peerTable.refresh()) );
		ap.add(new ActionButton("Set Stake...", 0xf5dc, e->{
			int row=peerTable.getSelectedRow();
			if (row<0) {
				JOptionPane.showMessageDialog(this,"Select a peer to set stake on");
				return;
			}
			Object val=peerTable.getValueAt(row, 0);
			AccountKey peerKey=AccountKey.parse(val);
			if (peerKey!=null) {
				Object stk=JOptionPane.showInputDialog(this,
						"Enter desired stake for peer "+peerKey,
						"Set Peer Stake...",
						JOptionPane.QUESTION_MESSAGE,
						SymbolIcon.get(0xf56e),
						null,
						"0.0");
				if (!(stk instanceof String)) return;
				AInteger amt=DecimalAmountField.parse((String) stk, 9, true);
				if (amt==null) {
					JOptionPane.showMessageDialog(this,"Input amount not valid. Must be a qantity in Convex Gold, e.g. '1000.01' ");
					return;
				}
				convex.transact("(set-stake "+peerKey+" "+amt+")").thenAcceptAsync(r->{
					if (r.isError()) {
						SwingUtilities.invokeLater(()->{
							JOptionPane.showMessageDialog(this,"Stake setting failed: "+r,"Staking problem",JOptionPane.ERROR_MESSAGE);
						});
					} 
					peerTable.refresh();
				});
			}
		}));
	}

}
