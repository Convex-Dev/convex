package convex.gui.wallet;

import javax.swing.JLabel;
import javax.swing.JPanel;

import convex.api.Convex;
import convex.core.data.ACell;
import convex.core.lang.RT;
import net.miginfocom.swing.MigLayout;

@SuppressWarnings("serial")
public class TokenInfoPanel extends JPanel {

	public TokenInfoPanel(Convex convex, TokenInfo token) {
		setLayout(new MigLayout("wrap 2"));
		
		add(new JLabel("Symbol: "));
		add(new JLabel(token.getSymbol()),"wrap");

		add(new JLabel("ID: "));
		ACell id=token.getID();
		String idString=RT.toString(id);
		add(new JLabel(idString),"wrap");

		
		add(new JLabel("Actor Description: "));
		JLabel descLabel=new JLabel("Loading...");
		if (id==null) {
			descLabel.setText("(Native coin)");
		} else {
			convex.query("(get (call *registry* (lookup (query (address "+id+")))) :name)").thenAcceptAsync(r->{
				if (r.isError()) {
					descLabel.setText(r.getErrorCode().toString());
				} else {
					descLabel.setText(RT.toString(r.getValue()));
				}
			});
		}
		add(descLabel,"wrap");

	}

}
