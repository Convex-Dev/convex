package convex.gui.wallet;

import convex.api.Convex;
import convex.gui.components.AbstractGUI;
import convex.gui.components.ActionButton;
import convex.gui.components.ActionPanel;
import net.miginfocom.swing.MigLayout;

@SuppressWarnings("serial")
public class SwapPanel extends AbstractGUI {

	protected Convex convex;

	private SwapPanel(String title) {
		super(title);
	}

	public SwapPanel(Convex convex, TokenInfo token) {
		this("Token Swap for account "+convex.getAddress());
		this.convex=convex;
		
		setLayout(new MigLayout("wrap 1"));
		
		add(new TokenComponent(convex,token));
		
		ActionPanel actionPanel=new ActionPanel();
		
		actionPanel.add(new ActionButton("Trade!",0xe933,e->{
			super.closeGUI();
		})); 
		actionPanel.add(new ActionButton("Close",0xe5c9,e->{
			super.closeGUI();
		})); 
		add(actionPanel,"dock south");
	}

}
