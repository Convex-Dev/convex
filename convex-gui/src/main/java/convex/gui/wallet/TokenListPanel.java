package convex.gui.wallet;

import java.awt.Color;
import java.awt.Component;

import javax.swing.DefaultListModel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;

import convex.api.Convex;
import convex.core.data.ACell;
import convex.core.lang.Reader;
import convex.core.util.ThreadUtils;
import convex.core.util.Utils;
import convex.gui.components.ActionButton;
import convex.gui.components.ActionPanel;
import convex.gui.components.ScrollyList;
import convex.gui.components.Toast;
import net.miginfocom.swing.MigLayout;

@SuppressWarnings("serial")
public class TokenListPanel extends JPanel {
	protected ScrollyList<TokenInfo> list;
	
	protected Convex convex;

	// Static so different lists can use same model
	static DefaultListModel<TokenInfo> model= new DefaultListModel<TokenInfo>();
	
	static TokenInfo defaultToken=TokenInfo.convexCoin();

	public TokenListPanel(Convex convex) {
		this.convex=convex;
		setLayout(new MigLayout("fill"));
		
		list=new ScrollyList<>(model, token->{
			return new TokenComponent(convex,token);
		});
		
		add(list,"dock center");
		// add(new AccountOverview(convex),"dock north");
		
		// Separate thread to get tokens, in case stuff fails
		ThreadUtils.runVirtual(()->{
			addTokenTracking(TokenInfo.get(convex,null));
			addTokenTracking(TokenInfo.getFungible(convex,"currency.USDF"));
			addTokenTracking(TokenInfo.getFungible(convex,"currency.GBPF"));
		});

		// add(new AccountChooserPanel(convex),"dock south");
		ActionPanel ap=new ActionPanel();
		ap.add(ActionButton.build("Track Token",0xe145,e->{
			String newID=JOptionPane.showInputDialog(TokenListPanel.this, "Enter Token ID");
			if (newID==null) return;
			try {
				ACell tokenID=newID.isBlank()?null:Reader.read(newID);
				TokenInfo token=TokenInfo.get(convex,tokenID);
				if (token==null) {
					Toast.display(TokenListPanel.this, "Token does not exist: "+tokenID,Color.ORANGE);
				} else if (model.contains(token)) {
					Toast.display(TokenListPanel.this, "Token already added",Color.ORANGE);
				} else {
					addTokenTracking(token);
				}
			} catch (Exception ex) {
				Toast.display(TokenListPanel.this, "Error adding token: "+ex.getMessage(),Color.ORANGE);
			}
		},"Add a token to the tracked token list"));
		ap.add(ActionButton.build("Refresh",0xe5d5,e->{
			list.refreshList(); 
		},"Refresh token details and balances"));
		add(ap,"dock south");
		
		ThreadUtils.runVirtual(this::updateLoop);
	}
	
	private static boolean addTokenTracking(TokenInfo tokenInfo) {
		if (tokenInfo==null) return false;
		if (model.contains(tokenInfo)) return false; // already tracked!
		model.addElement(tokenInfo);
		return true;
	}

	private void updateLoop() {
		while (true) {
			try {
				if (isShowing()) {
		
					Component[] comps=list.getListComponents();
					for (Component c: comps) {
						if ((c instanceof TokenComponent)&&c.isShowing()) {
							((TokenComponent)c).refresh(convex);
						}
					}
				}
				Thread.sleep(1000);
			} catch (InterruptedException e) {
				return;
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}

	public static TokenInfo getDefaultToken() {
		return defaultToken;
	}

	/**
	 * Gets the "other" token for a trade, which will be:
	 * 1. The default token token, if the token passed is already the default
	 * 2. The next other token in the list 
	 * 3. Null otherwise (no other token available)
	 * @param token Token selected for swap
	 * @return TokenInfo for other token to trade with
	 */
	public static TokenInfo getOtherToken(TokenInfo token) {
		TokenInfo other=TokenListPanel.getDefaultToken();
		if (Utils.equals(other.getID(),token.getID())) {
			// select next available token
			int n=model.getSize();
			for (int i=0; i<n; i++) {
				other=model.elementAt(i);
				if (!Utils.equals(other.getID(),token.getID()))	{
					return other;
				}
				other=null;
			}
		}
		return other;
	}
}
