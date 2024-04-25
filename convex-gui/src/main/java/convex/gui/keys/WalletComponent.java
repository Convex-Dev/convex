package convex.gui.keys;

import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTextArea;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.crypto.AKeyPair;
import convex.core.crypto.wallet.AWalletEntry;
import convex.gui.components.BaseListComponent;
import convex.gui.components.CodeLabel;
import convex.gui.components.DropdownMenu;
import convex.gui.components.Identicon;
import convex.gui.utils.Toolkit;
import net.miginfocom.swing.MigLayout;

@SuppressWarnings("serial")
public class WalletComponent extends BaseListComponent {
	
	@SuppressWarnings("unused")
	private static final Logger log = LoggerFactory.getLogger(WalletComponent.class.getName());

	JButton lockButton;
	JButton replButton;

	AWalletEntry walletEntry;

	JPanel buttons = new JPanel();

	private CodeLabel infoLabel;

	public WalletComponent(AWalletEntry initialWalletEntry) {
		this.walletEntry = initialWalletEntry;

		setLayout(new MigLayout("fillx"));

		// identicon
		JLabel identicon = new Identicon(walletEntry.getPublicKey());
		JPanel idPanel=new JPanel();
		idPanel.add(identicon);
		add(idPanel,"west"); // add to MigLayout

		// Wallet Address and info fields
		JPanel cPanel = new JPanel();
		cPanel.setLayout(new MigLayout("fillx"));
		//CodeLabel addressLabel = new CodeLabel(address.toString());
		//addressLabel.setFont(Toolkit.MONO_FONT);
		// cPanel.add(addressLabel,"span");
		
		infoLabel = new CodeLabel(getInfoString());
		cPanel.add(infoLabel,"span,growx");
		add(cPanel,"grow,shrink"); // add to MigLayout

		///// Buttons

		// lock button
		lockButton = new JButton("");
		buttons.add(lockButton);
		lockButton.addActionListener(e -> {
			if (walletEntry.isLocked()) {
				UnlockWalletDialog.offerUnlock(this,walletEntry);
			} else {
				try {
					String s=JOptionPane.showInputDialog(WalletComponent.this,"Enter lock password");
					if (s!=null) {
						walletEntry.lock(s.toCharArray());
					}	
				} catch (IllegalStateException e1) {
					e1.printStackTrace();
				}	
			}
			doUpdate();
		});
		
		// Menu Button
		JPopupMenu menu=new JPopupMenu();
		JMenuItem m1=new JMenuItem("Edit...");
		menu.add(m1);
		JMenuItem m2=new JMenuItem("Show seed...");
		m2.addActionListener(e-> {
			AKeyPair kp=walletEntry.getKeyPair();
			if (kp!=null) {
				StringBuilder sb=new StringBuilder();
				sb.append("\nEd25519 private seed:\n");
				sb.append("\n"+kp.getSeed()+"\n\n");
				sb.append("\nWarning: keep this private, it can be used to control your accounts\n");
				JTextArea text = new JTextArea(sb.toString());
				JOptionPane.showMessageDialog(WalletComponent.this, text,"Private Seed",JOptionPane.INFORMATION_MESSAGE);
			} else {
				JOptionPane.showMessageDialog(WalletComponent.this, "Keypair is locked, cannot access seed","Warning",JOptionPane.WARNING_MESSAGE);
			}
		});
		menu.add(m2);
		JMenuItem m3=new JMenuItem("Delete");
		m3.addActionListener(e-> {
			int confirm =JOptionPane.showConfirmDialog(WalletComponent.this, "Are you sure you want to delete this keypair from your keyring?","Confirm Delete",JOptionPane.WARNING_MESSAGE);
			if (confirm==JOptionPane.OK_OPTION) {
				KeyRingPanel.getListModel().removeElement(walletEntry);
			}
		});
		menu.add(m3);


		DropdownMenu menuButton=new DropdownMenu(menu);
		buttons.add(menuButton);
		
		// panel of buttons on right
		add(buttons,"east"); // add to MigLayout
		
		doUpdate();
	}


	private void doUpdate() {
		// TODO Auto-generated method stub
		resetTooltipText(lockButton);
		infoLabel.setText(getInfoString());
		Icon icon=walletEntry.isLocked()? Toolkit.LOCKED_ICON:Toolkit.UNLOCKED_ICON;
		this.lockButton.setIcon(icon);
	}


	private void resetTooltipText(JComponent b) {
		if (walletEntry.isLocked()) {
			b.setToolTipText("Unlock");
		} else {
			b.setToolTipText("Lock");
		}
	}

	private String getInfoString() {
		StringBuilder sb=new StringBuilder();
		sb.append("Public Key: " + walletEntry.getPublicKey()+"\n");
		sb.append("Status:     " + (walletEntry.isLocked()?"Locked":"Unlocked"));
		
		//sb.append("\n");
		//sb.append("Key: "+walletEntry.getAccountKey()+ "   Controller: "+as.getController());
		return sb.toString();
	}

}
