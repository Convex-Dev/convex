package convex.gui.components;

import javax.swing.DefaultComboBoxModel;
import javax.swing.JComboBox;

@SuppressWarnings("serial")
public class HostCombo extends JComboBox<String> {

	
	private static String[] defaultHosts= {"peer.convex.live:18888","localhost:18888"};
	private static DefaultComboBoxModel<String> model=new DefaultComboBoxModel<>(defaultHosts) {
		
	};
	
	public HostCombo() {
		setModel(model);
		setEditable(true);
	}

	public String getText() {
		return (String)getSelectedItem();
	}
	
	public void setText(String text) {
		this.getModel().setSelectedItem(text);
	}

	public static void registerGoodConnection(String target) {
		if (model.getIndexOf(target)<0) {
			model.addElement(target);
			model.setSelectedItem(target);
		}
	}
}
