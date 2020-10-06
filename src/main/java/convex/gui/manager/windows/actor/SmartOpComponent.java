package convex.gui.manager.windows.actor;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.logging.Logger;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;

import org.parboiled.common.Utils;

import convex.core.crypto.WalletEntry;
import convex.core.data.AList;
import convex.core.data.AVector;
import convex.core.data.AccountStatus;
import convex.core.data.Address;
import convex.core.data.Lists;
import convex.core.data.Symbol;
import convex.core.data.Syntax;
import convex.core.data.Vectors;
import convex.core.lang.IFn;
import convex.core.lang.RT;
import convex.core.lang.Reader;
import convex.core.lang.Symbols;
import convex.core.lang.impl.Fn;
import convex.core.store.Stores;
import convex.core.transactions.ATransaction;
import convex.core.transactions.Invoke;
import convex.gui.components.AccountChooserPanel;
import convex.gui.components.BaseListComponent;
import convex.gui.components.CodeLabel;
import convex.gui.components.Toast;
import convex.gui.manager.PeerManager;
import convex.gui.utils.Toolkit;
import convex.net.Connection;
import convex.net.ResultConsumer;

@SuppressWarnings("serial")
public class SmartOpComponent extends BaseListComponent {

	protected ActorInvokePanel parent;
	protected Object sym;
	int paramCount;

	/**
	 * Fields for each argument by position.
	 * 
	 * Null entry used for funds offer
	 */
	private HashMap<Integer, JTextField> paramFields = new HashMap<>();

	private static final Logger log = Logger.getLogger(SmartOpComponent.class.getName());

	public SmartOpComponent(ActorInvokePanel parent, Address contract, Symbol sym) {
		this.parent = parent;
		this.sym = sym;

		setFont(Toolkit.SMALL_MONO_FONT);
		setLayout(new BorderLayout(0, 0));

		CodeLabel opName = new CodeLabel(sym.toString());
		opName.setFont(Toolkit.MONO_FONT);
		add(opName, BorderLayout.NORTH);

		JPanel paramPanel = new JPanel();
		paramPanel.setLayout(new GridLayout(0, 3, 4, 4)); // 3 columns, small hgap and vgap

		AccountStatus as = PeerManager.getLatestState().getAccount(contract);

		IFn<Object> fn = (IFn<Object>) as.getActorFunction(sym);

		// Function might be a map or set
		AVector<Syntax> params = (fn instanceof Fn) ? ((Fn<?>) fn).getParams()
				: Vectors.of(Syntax.create(Symbols.FOO));
		paramCount = params.size();

		for (int i = 0; i < paramCount; i++) {
			Symbol paramSym = params.get(i).getValue();
			paramPanel.add(new ParamLabel(Utils.toString(paramSym)));
			JTextField argBox = new ArgBox();
			paramPanel.add(argBox);
			paramFields.put(i, argBox);
			paramPanel.add(new JLabel("")); // TODO: descriptions?
		}
		paramPanel.add(new ParamLabel("<offer funds>"));
		JTextField offerBox = new ArgBox();
		paramFields.put(null, offerBox);
		paramPanel.add(offerBox);
		paramPanel.add(new JLabel("Offer funds (0 or blank for no offer)"));

		add(paramPanel, BorderLayout.CENTER);

		JPanel aPanel = new JPanel();
		aPanel.setLayout(new FlowLayout(FlowLayout.LEFT));
		JButton execButton = new JButton("Execute");
		aPanel.add(execButton);
		execButton.addActionListener(e -> execute());

		add(aPanel, BorderLayout.SOUTH);

	}

	private final ResultConsumer receiveAction = new ResultConsumer() {
		@Override
		protected void handleResult(Object m) {
			showResult(m);
		}

		@Override
		protected void handleError(long id, Object code, Object msg) {
			showError(code,msg);
		}
	};

	private void execute() {
		InetSocketAddress addr = PeerManager.getDefaultPeer().getHostAddress();

		AVector<Object> args = Vectors.empty();
		for (int i = 0; i < paramCount; i++) {
			JTextField argBox = paramFields.get(i);
			String s = argBox.getText();
			Object arg = (s.isBlank()) ? null : Reader.read(s);
			args = args.conj(arg);
		}
		String offerString = paramFields.get(null).getText();
		Long offer = (offerString.isBlank()) ? null : Long.parseLong(offerString.trim());

		AList<Object> rest = Lists.of(Lists.create(args).cons(sym)); // (foo 1 2 3)
		if (offer != null) {
			rest = rest.cons(offer);
		}

		try {
			Object message = RT.cons(Symbols.CALL, parent.contract, rest);

			long id;
			
			// connect to Peer as a client
			Connection peerConnection = Connection.connect(addr, receiveAction, Stores.getGlobalStore());
			
			AccountChooserPanel execPanel = parent.execPanel;
			String mode = execPanel.getMode();
			if (mode.equals("Query")) {
				WalletEntry we = execPanel.getWalletEntry();
				if (we == null) {
					id = peerConnection.sendQuery(message);
				} else {
					id = peerConnection.sendQuery(message, we.getAddress());
				}
			} else if (mode.equals("Transact")) {
				WalletEntry we = execPanel.getWalletEntry();
				if ((we == null) || (we.isLocked())) {
					JOptionPane.showMessageDialog(this,
							"Please select an unlocked wallet address to use for transactions before sending");
					return;
				}
				Address address = we.getAddress();
				AccountStatus as = PeerManager.getLatestState().getAccount(address);
				if (as == null) {
					JOptionPane.showMessageDialog(this, "Cannot send transaction: account does not exist");
					return;
				}
				long nonce = as.getSequence() + 1;
				ATransaction trans = Invoke.create(nonce, message);
				id = peerConnection.sendTransaction(we.sign(trans));
			} else {
				throw new Error("Unrecognosed REPL mode: " + mode);
			}
			log.info("Message sent with ID: " + id + " : " + message);
		} catch (IOException e) {
			log.warning(e.getMessage());
		}

	}

	private void showError(Object code, Object msg) {
		String resultString = "Error executing transaction: " + code + " "+msg;
		log.info(resultString);
		Toast.display(parent, resultString, Toast.FAIL);
	}

	private void showResult(Object v) {
		String resultString = "Transaction executed successfully";
		log.info(resultString);
		Toast.display(parent, resultString, Toast.SUCCESS);
	}
}
