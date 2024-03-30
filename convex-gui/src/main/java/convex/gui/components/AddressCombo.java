package convex.gui.components;

import java.util.Collection;

import javax.swing.ComboBoxModel;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.plaf.basic.BasicComboBoxEditor;

import convex.core.data.Address;
import convex.core.data.Vectors;
import convex.core.util.Utils;
import convex.gui.utils.Toolkit;
import net.miginfocom.swing.MigLayout;

@SuppressWarnings("serial")
public class AddressCombo extends JComboBox<Address> {
	private static Address PROTOTYPE=Address.create(100000);
	
	private class AddressEditor extends BasicComboBoxEditor {	
		@Override 
		public Object getItem() {
			return Address.parse(editor.getText());
		}
	}

	public AddressCombo(ComboBoxModel<Address> model) {
		super(model);
		setEditor(new AddressEditor());
		setEditable(true);
		this.setFont(Toolkit.MONO_FONT);
	}
	
	public AddressCombo() {
		this(new DefaultComboBoxModel<Address>());
	}
	
	public AddressCombo(Address... addresses) {
		this(new DefaultComboBoxModel<Address>(addresses));
	}
	
	public AddressCombo(Collection<Address> addresses) {
		this(new DefaultComboBoxModel<Address>((Address[]) addresses.toArray(new Address[0])));
	}
	
	public Address getPrototypeDisplayValue() {
		return PROTOTYPE;
	}
	
	public static void main (String... args) {
		Toolkit.init();
		JPanel p=new JPanel();
		p.setLayout(new MigLayout("insets 20 20 20 20, wrap 1"));
		
		AddressCombo ac=new AddressCombo(Vectors.of(Address.ZERO,Address.create(12),Address.MAX_VALUE));
		p.add(ac);
		
		JLabel sel=new JLabel("Nothing selected");		
		p.add(sel);
		
		ac.addItemListener(e->{
			Object o=ac.getSelectedItem();
			sel.setText(o+ " : "+Utils.getClassName(o));
		});
		
		Toolkit.showMainFrame(p);
	}
}
