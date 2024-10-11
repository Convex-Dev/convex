package convex.gui.actor;

import java.awt.BorderLayout;
import java.awt.Dimension;

import javax.swing.JPanel;
import javax.swing.JTextArea;

import convex.core.cvm.State;
import convex.core.cvm.AccountStatus;
import convex.core.data.Address;
import convex.core.data.Cells;
import convex.core.lang.RT;
import convex.core.text.Text;
import convex.gui.models.StateModel;
import convex.gui.utils.Toolkit;

@SuppressWarnings("serial")
public class AccountInfoPanel extends JPanel {

	protected Address addr;
	protected JTextArea infoArea;

	public AccountInfoPanel(StateModel<State> manager, Address address) {
		this.addr = address;
		setLayout(new BorderLayout(0, 0));

		this.setPreferredSize(new Dimension(600, 400));

		infoArea = new JTextArea();
		add(infoArea, BorderLayout.CENTER);
		infoArea.setBackground(null);
		infoArea.setFont(Toolkit.MONO_FONT);

		manager.addPropertyChangeListener(e -> {
			updateInfo((State) e.getNewValue());
		});
		updateInfo(manager.getValue());
	}

	private void updateInfo(State latestState) {

		AccountStatus as = latestState.getAccount(addr);
		
		infoArea.setText(getInfoText(addr,as));
	}
	
	public static String getInfoText(Address actor,AccountStatus as) {
		if (as==null) {
			return "Account "+actor+" does not exist in current State\n";
		}
		
		StringBuilder sb = new StringBuilder();		
		
		sb.append("Account:        " + actor.toString() + "\n");
		sb.append("\n");
		sb.append("Account Key:    " + as.getAccountKey() + "\n");
		sb.append("Sequence:       " + as.getSequence() + "\n");
		sb.append("Balance:        " + Text.toFriendlyBalance(as.getBalance()) + "\n");
		sb.append("Mem. Allowance: " + Text.toFriendlyNumber(as.getMemory()) + "\n");
		sb.append("Allowance:      " + as.getMemory() + "\n");
		sb.append("Controller:     " + as.getController() + "\n");
		sb.append("Parent:         " + as.getParent() + "\n");
		sb.append("Env Size:       " + RT.count(as.getEnvironment()) + "\n");
		sb.append("Holding Size:   " + RT.count(as.getHolding(actor)) + "\n");
		
		sb.append("\n");
		
		sb.append("Storage Size:   "+Cells.storageSize(as)+"\n");
		
		sb.append("\n");
		
		return sb.toString();
	}

}
