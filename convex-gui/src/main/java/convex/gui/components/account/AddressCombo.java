package convex.gui.components.account;

import java.awt.event.FocusAdapter;
import java.util.Collection;
import java.util.TreeSet;

import javax.swing.DefaultComboBoxModel;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.plaf.basic.BasicComboBoxEditor;

import convex.core.cvm.Address;
import convex.core.data.Vectors;
import convex.core.text.AddressFormat;
import convex.core.util.Utils;
import convex.gui.utils.Toolkit;
import net.miginfocom.swing.MigLayout;

@SuppressWarnings("serial")
public class AddressCombo extends JComboBox<Address> {
	private static Address PROTOTYPE=Address.create(100000);
	
	private static TreeSet<Address> usedAddresses=new TreeSet<>();
	
	private class AddressEditor extends BasicComboBoxEditor {	
		@Override 
		public Address getItem() {
			try {
				return (Address) AddressFormat.INSTANCE.parseObject(editor.getText());
			} catch (Exception e) {
				return null;
			}
		}
		
		@Override
		protected AddressField createEditorComponent() {
			AddressField fld= new AddressField();
			fld.addFocusListener(new FocusAdapter() {
				public void focusGained(java.awt.event.FocusEvent evt) {
			        SwingUtilities.invokeLater(new Runnable() {
			            @Override
			            public void run() {
			                fld.select(1, fld.getText().length());;
			            }
			        });
			    }
			});
			return fld;
		}
	}

	public AddressCombo(DefaultComboBoxModel<Address> model) {
		super(model);
		setEditor(new AddressEditor());
		setEditable(true);
		this.addItemListener(e->{
			Address address=(Address) getSelectedItem();
			if ((address!=null)&&(model.getIndexOf(address)<0)) {
				model.addElement(address);
				usedAddresses.add(address);
			}
			Toolkit.relinquishFocus(AddressCombo.this);
		});
	}

	public AddressCombo() {
		this(usedAddresses);
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

	public Address getAddress() {
		Object a = getSelectedItem();
		if (a instanceof Address) return (Address)a;
		return null;
	}
}
