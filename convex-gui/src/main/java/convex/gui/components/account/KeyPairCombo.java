package convex.gui.components.account;

import java.awt.Component;

import javax.swing.ComboBoxModel;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.ListCellRenderer;

import convex.core.crypto.AKeyPair;
import convex.core.crypto.wallet.AWalletEntry;
import convex.core.crypto.wallet.HotWalletEntry;
import convex.core.util.Utils;
import convex.gui.components.CodeLabel;
import convex.gui.components.Identicon;
import convex.gui.utils.Toolkit;
import net.miginfocom.swing.MigLayout;

@SuppressWarnings("serial")
public class KeyPairCombo extends JComboBox<AWalletEntry> {

	public static class KeyPairModel extends DefaultComboBoxModel<AWalletEntry> {

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
			setText("0x"+entry.getPublicKey().toHexString(12)+"...");
			setIcon(Identicon.createIcon(entry.getIdenticonData(),24));
			return this;
		}
	}

	private static AKeyPair TEMP_KEYPAIR = AKeyPair.createSeeded(1337);
	private static HotWalletEntry PROTOTYPE=HotWalletEntry.create(TEMP_KEYPAIR);

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
		model.addElement(PROTOTYPE);
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
}
