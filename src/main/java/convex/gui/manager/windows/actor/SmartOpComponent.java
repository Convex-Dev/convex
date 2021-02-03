package convex.gui.manager.windows.actor;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.logging.Logger;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;

import org.parboiled.common.Utils;

import convex.api.Convex;
import convex.core.Result;
import convex.core.crypto.WalletEntry;
import convex.core.data.ACell;
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
import convex.core.transactions.ATransaction;
import convex.core.transactions.Invoke;
import convex.gui.components.AccountChooserPanel;
import convex.gui.components.BaseListComponent;
import convex.gui.components.CodeLabel;
import convex.gui.components.Toast;
import convex.gui.manager.PeerManager;
import convex.gui.utils.Toolkit;


@SuppressWarnings("serial")
public class SmartOpComponent extends BaseListComponent {

	protected ActorInvokePanel parent;
	protected Symbol sym;
	int paramCount;

	/**
	 * Fields for each argument by position.
	 * 
	 * Null entry used for funds offer
	 */
	private HashMap<Integer, JTextField> paramFields = new HashMap<>();

	private static final Logger log = Logger.getLogger(SmartOpComponent.class.getName());

	@SuppressWarnings("rawtypes")
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

		IFn fn = (IFn) as.getExportedFunction(sym);

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

	private void execute() {
		InetSocketAddress addr = PeerManager.getDefaultPeer().getHostAddress();

		AVector<ACell> args = Vectors.empty();
		for (int i = 0; i < paramCount; i++) {
			JTextField argBox = paramFields.get(i);
			String s = argBox.getText();
			ACell arg = (s.isBlank()) ? null : Reader.read(s);
			args = args.conj(arg);
		}
		String offerString = paramFields.get(null).getText();
		Long offer = (offerString.isBlank()) ? null : Long.parseLong(offerString.trim());

		AList<ACell> rest = Lists.of(Lists.create(args).cons(sym)); // (foo 1 2 3)
		if (offer != null) {
			rest = rest.cons(RT.cvm(offer));
		}

		try {
			ACell message = RT.cons(Symbols.CALL, parent.contract, rest);
			
			AccountChooserPanel execPanel = parent.execPanel;
			WalletEntry we = execPanel.getWalletEntry();
			Address myAddress=we.getAddress();
			
			// connect to Peer as a client
			Convex peerConnection = Convex.connect(addr, we.getAddress(),we.getKeyPair());
			
			String mode = execPanel.getMode();
			Result r=null;
			if (mode.equals("Query")) {
				r=peerConnection.querySync(message);
			} else if (mode.equals("Transact")) {
				if (we.isLocked()) {
					JOptionPane.showMessageDialog(this,
							"Please select an unlocked wallet address to use for transactions before sending");
					return;
				}
				
				ATransaction trans = Invoke.create(myAddress,-1, message);
				r = peerConnection.transactSync(trans);
			} else {
				throw new Error("Unexpected mode: "+mode);
			}
			if (r.isError()) {
				showError(r.getErrorCode(),r.getValue());
			} else {
				showResult(r.getValue());
			}
			
		} catch (Throwable e) {
			log.warning(e.getMessage());
			Toast.display(parent, "Unexpected Error: "+e.getMessage(), Toast.FAIL);

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
