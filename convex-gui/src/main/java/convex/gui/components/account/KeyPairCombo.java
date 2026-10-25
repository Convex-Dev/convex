package convex.gui.components.account;

import java.awt.Component;
import java.util.List;

import javax.swing.AbstractListModel;
import javax.swing.ComboBoxModel;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.ListCellRenderer;
import javax.swing.ListModel;
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
import convex.gui.keys.KeyRing;
import convex.gui.utils.Toolkit;
import net.miginfocom.swing.MigLayout;

/**
 * Combo box for choosing a key pair from a list of wallet entries.
 */
@SuppressWarnings("serial")
public class KeyPairCombo extends JComboBox<AWalletEntry> implements AutoCloseable {

	/**
	 * Combo box model over a list of wallet entries (by default the global key
	 * ring), with an extra null entry for "no key pair". Selection is per model.
	 */
	public static class KeyPairModel extends AbstractListModel<AWalletEntry> implements ComboBoxModel<AWalletEntry>, AutoCloseable {

		private final ListModel<AWalletEntry> underlying;
		private AWalletEntry selected;

		/** Forwards changes in the underlying list to this model's listeners */
		private final ListDataListener forwarder=new ListDataListener() {
			@Override
			public void intervalAdded(ListDataEvent e) {
				fireIntervalAdded(KeyPairModel.this, e.getIndex0(), e.getIndex1());
			}

			@Override
			public void intervalRemoved(ListDataEvent e) {
				fireIntervalRemoved(KeyPairModel.this, e.getIndex0(), e.getIndex1());
			}

			@Override
			public void contentsChanged(ListDataEvent e) {
				fireContentsChanged(KeyPairModel.this, e.getIndex0(), e.getIndex1());
			}
		};

		public KeyPairModel(ListModel<AWalletEntry> underlying) {
			this.underlying=underlying;
			underlying.addListDataListener(forwarder);
		}

		public KeyPairModel() {
			this(KeyRing.get().listModel());
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
		public void setSelectedItem(Object anItem) {
			this.selected=(AWalletEntry)anItem;
			fireContentsChanged(this, -1, -1);
		}

		@Override
		public Object getSelectedItem() {
			return this.selected;
		}

		/** Stops following the underlying list */
		@Override
		public void close() {
			underlying.removeListDataListener(forwarder);
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
		
		this.setFocusable(false);
	}
	
	public AWalletEntry getPrototypeDisplayValue() {
		return PROTOTYPE;
	}
	
	public static void main (String... args) {
		Toolkit.init();
		JPanel p=new JPanel();
		p.setLayout(new MigLayout("insets 20 20 20 20, wrap 1"));
		
		KeyRing.get().add(HotWalletEntry.create(AKeyPair.generate(),"Test key"));
		KeyPairCombo kpCombo=new KeyPairCombo(new KeyPairModel());
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
		Object a = getModel().getSelectedItem();
		return (AWalletEntry)a;
	}

	public static KeyPairCombo forConvex(Convex convex) {
		AKeyPair kp=convex.getKeyPair();
		return create(kp);
	}
	
	public static KeyPairCombo create() {
		KeyPairModel model=new KeyPairModel();
		List<AWalletEntry> entries=KeyRing.get().getEntries();
		if (!entries.isEmpty()) {
			model.setSelectedItem(entries.get(0));
		}
		return new KeyPairCombo(model);
	}

	public static KeyPairCombo create(AKeyPair kp) {
		KeyPairModel model=new KeyPairModel();
		if (kp!=null) {
			AccountKey publicKey=kp.getAccountKey();
			AWalletEntry we=KeyRing.get().find(publicKey);
			if (we==null) {
				we=new HotWalletEntry(kp,null);
			}
			model.setSelectedItem(we);
		}
 		return new KeyPairCombo(model);
	}

	/** Stops following the key ring. Called when the owning window closes. */
	@Override
	public void close() {
		if (getModel() instanceof KeyPairModel model) model.close();
	}
}
