package convex.gui.keys;

import java.awt.Color;

import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;

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

	JPanel buttons;

	private CodeLabel infoLabel;

	public WalletComponent(AWalletEntry initialWalletEntry) {
		this.walletEntry = initialWalletEntry;

		setLayout(new MigLayout("aligny center"));

		//////////  identicon
		JLabel identicon = new Identicon(walletEntry.getPublicKey(),Toolkit.IDENTICON_SIZE*2);
		JPanel idPanel=new JPanel();
		idPanel.add(identicon);
		add(idPanel); // add to MigLayout

		
		/////////// Wallet Address and info fields
		JPanel cPanel = new JPanel();
		cPanel.setLayout(new MigLayout());
		//CodeLabel addressLabel = new CodeLabel(address.toString());
		//addressLabel.setFont(Toolkit.MONO_FONT);
		// cPanel.add(addressLabel,"span");
		
		infoLabel = new CodeLabel(getInfoString());
		infoLabel.setFont(Toolkit.SMALL_MONO_FONT);
		cPanel.add(infoLabel,"dock center");
		//add(cPanel,"dock center"); // add to MigLayout
		add(cPanel); // add to MigLayout

		//////////// Buttons
		buttons = new JPanel();
		buttons.setLayout(new MigLayout());
		
		// lock button
		lockButton = new JButton("");
		buttons.add(lockButton);
		lockButton.addActionListener(e -> {
			if (walletEntry.isLocked()) {
				UnlockWalletDialog.offerUnlock(this,walletEntry);
			} else {
				try {
					if (walletEntry.needsLockPassword()) {
						String s=JOptionPane.showInputDialog(WalletComponent.this,"Enter lock password");
						if (s!=null) {
							walletEntry.lock(s.toCharArray());
						}	
					} else {
						walletEntry.lock();
					}
				
				} catch (Exception e1) {
					e1.printStackTrace();
				}	
			}
			doUpdate();
		});
		
		// Menu Button
		JPopupMenu menu=new JPopupMenu();
		//JMenuItem m1=new JMenuItem("Edit...");
		//menu.add(m1);
		JMenuItem m2=new JMenuItem("Show seed...");
		m2.addActionListener(e-> {
			AKeyPair kp=walletEntry.getKeyPair();
			if (kp!=null) {
				JPanel panel=new JPanel();
				panel.setLayout(new MigLayout("wrap 1","[200]"));
				panel.add(new Identicon(kp.getAccountKey(),Toolkit.IDENTICON_SIZE_LARGE),"align center");
				
				panel.add(Toolkit.withTitledBorder("Ed25519 Private Seed",new CodeLabel(kp.getSeed().toString()))); 
				panel.add(Toolkit.makeNote("WARNING: keep this private, it can be used to control your account(s)"),"grow");
				panel.setBorder(Toolkit.createDialogBorder());
				JOptionPane.showMessageDialog(WalletComponent.this, panel,"Ed25519 Private Seed",JOptionPane.INFORMATION_MESSAGE);
			} else {
				JOptionPane.showMessageDialog(WalletComponent.this, "Keypair is locked, cannot access seed","Warning",JOptionPane.WARNING_MESSAGE);
			}
		});
		menu.add(m2);
		JMenuItem m3=new JMenuItem("Remove...");
		m3.addActionListener(e-> {
			int confirm =JOptionPane.showConfirmDialog(WalletComponent.this, "Are you sure you want to delete this keypair from your keyring?","Confirm Delete",JOptionPane.WARNING_MESSAGE);
			if (confirm==JOptionPane.OK_OPTION) {
				KeyRingPanel.getListModel().removeElement(walletEntry);
			}
		});
		menu.add(m3);

		DropdownMenu menuButton=new DropdownMenu(menu); 
		menuButton.setToolTipText("Settings and special actions for this key");
		buttons.add(menuButton);
		
		// panel of buttons on right
		add(buttons,"dock east"); // add to MigLayout
		
		doUpdate();
	}


	private void doUpdate() {
		// TODO Auto-generated method stub
		resetTooltipText(lockButton);
		infoLabel.setText(getInfoString());
		Icon icon=walletEntry.isLocked()? Toolkit.LOCKED_ICON:Toolkit.UNLOCKED_ICON;
		
		this.lockButton.setIcon(icon);
		this.lockButton.setForeground(Color.WHITE);
	}


	private void resetTooltipText(JComponent b) {
		if (walletEntry.isLocked()) {
			b.setToolTipText("Currently locked. Press to unlock.");
		} else {
			b.setToolTipText("Currently unlocked. Press to lock.");
		}
	}

	private String getInfoString() {
		StringBuilder sb=new StringBuilder();
		sb.append("Public Key: " + walletEntry.getPublicKey()+"\n");
		// sb.append("Status:     " + (walletEntry.isLocked()?"Locked":"Unlocked")+"\n");
		sb.append("Source:     " + walletEntry.getSource());
		
		//sb.append("\n");
		//sb.append("Key: "+walletEntry.getAccountKey()+ "   Controller: "+as.getController());
		return sb.toString();
	}

}
