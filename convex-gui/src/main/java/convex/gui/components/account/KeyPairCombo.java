package convex.gui.components.account;

import java.awt.Component;

import javax.swing.ComboBoxModel;
import javax.swing.DefaultListModel;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.ListCellRenderer;
import javax.swing.event.ListDataEvent;
import javax.swing.event.ListDataListener;

import convex.api.Convex;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.wallet.AWalletEntry;
import convex.core.crypto.wallet.HotWalletEntry;
import convex.core.data.AccountKey;
import convex.core.data.Blobs;
import convex.core.util.Utils;
import convex.gui.components.CodeLabel;
import convex.gui.components.Identicon;
import convex.gui.keys.KeyRingPanel;
import convex.gui.utils.Toolkit;
import net.miginfocom.swing.MigLayout;

/**
 * Combo box for choosing a key pair from a list of wallet entries.
 */
@SuppressWarnings("serial")
public class KeyPairCombo extends JComboBox<AWalletEntry> {

	public static class KeyPairModel implements ComboBoxModel<AWalletEntry> {

		private DefaultListModel<AWalletEntry> underlying;
		private AWalletEntry selected;
		
		public KeyPairModel(DefaultListModel<AWalletEntry> underlying) {
			this.underlying=underlying;
		}
		
		public KeyPairModel() {
			this(KeyRingPanel.getListModel());
		}

		@Override
		public int getSize() {
			return underlying.getSize()+1;
		}

		@Override
		public AWalletEntry getElementAt(int index) {
			int n=underlying.getSize();
			if (index>=n) return null;
			return underlying.getElementAt(index);
		}

		@Override
		public void addListDataListener(ListDataListener l) {
			underlying.addListDataListener(l);
		}

		@Override
		public void removeListDataListener(ListDataListener l) {
			underlying.removeListDataListener(l);
		}

		@Override
		public void setSelectedItem(Object anItem) {
			this.selected=(AWalletEntry)anItem;
			ListDataListener[] listeners = underlying.getListDataListeners();
			ListDataEvent e=new ListDataEvent(this,ListDataEvent.CONTENTS_CHANGED,0,0);
			for (ListDataListener l: listeners) {
				l.contentsChanged(e);
			}
		}

		@Override
		public Object getSelectedItem() {
			return this.selected;
		}

		public void addElement(AWalletEntry entry) {
			underlying.addElement(entry);
		}

	}
	
	public class KeyPairRenderer extends JLabel implements ListCellRenderer<AWalletEntry> {
		public KeyPairRenderer() {
			setOpaque(true);
			setVerticalAlignment(CENTER);
		}

		@Override
		public Component getListCellRendererComponent(JList<? extends AWalletEntry> list, AWalletEntry value, int index,
				boolean isSelected, boolean cellHasFocus) {
			AWalletEntry entry= (AWalletEntry)value;
			if (entry!=null) {
				AccountKey pubKey=entry.getPublicKey();
				setText("0x"+pubKey.toChecksumHex().substring(0,16)+"...");
				setIcon(Identicon.createIcon(entry.getIdenticonData(),21));				
			} else {
				setText("<no key pair set>");
				setIcon(Identicon.createIcon(Blobs.empty(),21));
			}
			return this;
		}
	}

	private static AKeyPair TEMP_KEYPAIR = AKeyPair.createSeeded(1337);
	private static HotWalletEntry PROTOTYPE=HotWalletEntry.create(TEMP_KEYPAIR, "Prototype key");

	public KeyPairCombo(ComboBoxModel<AWalletEntry> model) {
		this.setModel(model);
		this.setRenderer(new KeyPairRenderer());
	}
	
	public AWalletEntry getPrototypeDisplayValue() {
		return PROTOTYPE;
	}
	
	public static void main (String... args) {
		Toolkit.init();
		JPanel p=new JPanel();
		p.setLayout(new MigLayout("insets 20 20 20 20, wrap 1"));
		
		KeyPairModel model=new KeyPairModel();
		model.addElement(HotWalletEntry.create(AKeyPair.generate(),"Test key"));
		KeyPairCombo kpCombo=new KeyPairCombo(model);
		p.add(kpCombo);
		
		CodeLabel sel=new CodeLabel("Nothing selected");		
		p.add(sel);
		
		kpCombo.addItemListener(e->{
			Object o=kpCombo.getSelectedItem();
			sel.setText(o+ " : "+Utils.getClassName(o));
		});
		
		Toolkit.showMainFrame(p);
	}

	public AWalletEntry getWalletEntry() {
		Object a = getSelectedItem();
		return (AWalletEntry)a;
	}

	public static KeyPairCombo forConvex(Convex convex) {
		AKeyPair kp=convex.getKeyPair();
		return create(kp);
	}

	public static KeyPairCombo create(AKeyPair kp) {
		KeyPairModel model=new KeyPairModel();
		if (kp!=null) {
			AccountKey publicKey=kp.getAccountKey();
			AWalletEntry we=KeyRingPanel.getKeyRingEntry(publicKey);
			if (we==null) {
				we=new HotWalletEntry(kp,null);
			}
			model.setSelectedItem(we);
		}
 		return new KeyPairCombo(model);
	}
}
