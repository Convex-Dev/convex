package convex.gui.wallet;

import java.awt.Color;
import java.awt.Component;
import java.awt.EventQueue;

import javax.swing.JOptionPane;
import javax.swing.JPanel;

import convex.api.Convex;
import convex.core.data.ACell;
import convex.core.lang.Reader;
import convex.core.util.ThreadUtils;
import convex.gui.components.ActionButton;
import convex.gui.components.ActionPanel;
import convex.gui.components.ScrollyList;
import convex.gui.components.Toast;
import net.miginfocom.swing.MigLayout;

/**
 * Panel listing the tokens tracked on the connection's network, with balances
 */
@SuppressWarnings("serial")
public class TokenListPanel extends JPanel implements AutoCloseable {
	/** Shown once the network is known. Set under lock. */
	protected volatile ScrollyList<TokenInfo> list;

	protected Convex convex;

	/** The connection's network, null until found */
	private volatile NetworkState network;

	private final Thread updateThread;

	private final Object lock=new Object();
	private boolean closed=false;

	static TokenInfo defaultToken=TokenInfo.convexCoin();

	public TokenListPanel(Convex convex) {
		this.convex=convex;
		setLayout(new MigLayout("fill"));

		// Find the network and default tokens off the EDT, in case the peer is slow or fails
		ThreadUtils.runVirtual("token-init", ()->{
			try {
				NetworkState net=NetworkState.of(convex);
				net.track(TokenInfo.get(convex,null));
				net.track(TokenInfo.getFungible(convex,"currency.USDF"));
				net.track(TokenInfo.getFungible(convex,"currency.GBPF"));
				EventQueue.invokeLater(()->showTokens(net));
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} catch (Exception e) {
				e.printStackTrace();
			}
		});

		// add(new AccountChooserPanel(convex),"dock south");
		ActionPanel ap=new ActionPanel();
		ap.add(ActionButton.build("Track Token",0xe145,e->{
			NetworkState net=network;
			if (net==null) {
				Toast.display(TokenListPanel.this, "Token list is still loading",Color.ORANGE);
				return;
			}
			String newID=JOptionPane.showInputDialog(TokenListPanel.this, "Enter Token ID");
			if (newID==null) return;
			try {
				ACell tokenID=newID.isBlank()?null:Reader.read(newID);
				TokenInfo token=TokenInfo.get(convex,tokenID);
				if (token==null) {
					Toast.display(TokenListPanel.this, "Token does not exist: "+tokenID,Color.ORANGE);
				} else if (net.isTracked(token)) {
					Toast.display(TokenListPanel.this, "Token already added",Color.ORANGE);
				} else {
					net.track(token);
				}
			} catch (Exception ex) {
				Toast.display(TokenListPanel.this, "Error adding token: "+ex.getMessage(),Color.ORANGE);
			}
		},"Add a token to the tracked token list"));
		ap.add(ActionButton.build("Refresh",0xe5d5,e->{
			ScrollyList<TokenInfo> l=list;
			if (l!=null) l.refreshList();
		},"Refresh token details and balances"));
		add(ap,"dock south");

		updateThread=Thread.ofVirtual().name("token-update").start(this::updateLoop);
	}

	/** Shows the network's tracked tokens. Runs on the EDT. */
	private void showTokens(NetworkState net) {
		synchronized (lock) {
			if (closed) return;
			network=net;
			list=new ScrollyList<>(net.trackedTokens(), token->{
				return new TokenComponent(convex,net,token);
			});
		}
		add(list,"dock center");
		revalidate();
	}

	/** Stops the update loop and the token list. Called when the owning window closes. */
	@Override
	public void close() {
		updateThread.interrupt();
		synchronized (lock) {
			closed=true;
			// The list may have been shown after the window gathered its components
			if (list!=null) list.close();
		}
	}

	private void updateLoop() {
		while (!Thread.currentThread().isInterrupted()) {
			try {
				ScrollyList<TokenInfo> l=list;
				if ((l!=null)&&isShowing()) {

					Component[] comps=l.getListComponents();
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
}
