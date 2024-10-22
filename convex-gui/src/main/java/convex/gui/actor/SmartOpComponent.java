package convex.gui.actor;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.HashMap;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.api.Convex;
import convex.core.Result;
import convex.core.data.ACell;
import convex.core.data.AList;
import convex.core.data.AVector;
import convex.core.cvm.AccountStatus;
import convex.core.cvm.transactions.ATransaction;
import convex.core.cvm.transactions.Invoke;
import convex.core.data.Address;
import convex.core.data.Lists;
import convex.core.data.Symbol;
import convex.core.data.Vectors;
import convex.core.lang.RT;
import convex.core.lang.Reader;
import convex.core.data.Symbols;
import convex.core.lang.impl.Fn;
import convex.gui.components.BaseListComponent;
import convex.gui.components.CodeLabel;
import convex.gui.components.Toast;
import convex.gui.components.account.AccountChooserPanel;
import convex.gui.utils.Toolkit;
import net.miginfocom.swing.MigLayout;


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

	private static final Logger log = LoggerFactory.getLogger(SmartOpComponent.class.getName());

	public SmartOpComponent(ActorInvokePanel parent, Address contract, Symbol sym) {
		this.parent = parent;
		this.sym = sym;

		setFont(Toolkit.MONO_FONT);
		setLayout(new BorderLayout(0, 0));

		// Name and description
		CodeLabel opName = new CodeLabel(sym.toString());
		opName.setFont(Toolkit.MONO_FONT);
		add(opName, BorderLayout.NORTH);
		
		// Parameters
		JPanel paramPanel = new JPanel();
		paramPanel.setLayout(new MigLayout("wrap 3","[200][300,fill][400]","")); // 3 columns, small hgap and vgap

		AccountStatus as = parent.getLatestState().getAccount(contract);

		convex.core.cvm.AFn<?> fn = as.getCallableFunction(sym);

		// Function might be a map or set
		AVector<ACell> params = (fn instanceof Fn) ? ((Fn<?>) fn).getParams()
				: Vectors.of(Symbols.FOO);
		paramCount = params.size();

		for (int i = 0; i < paramCount; i++) {
			ACell paramSym = params.get(i);
			paramPanel.add(new ParamLabel(RT.str(paramSym).toString()));
			JTextField argBox = new ArgBox();
			paramPanel.add(argBox);
			paramFields.put(i, argBox);
			paramPanel.add(new JLabel("")); // TODO: descriptions?
		}
		paramPanel.add(new ParamLabel("*offer*"));
		JTextField offerBox = new ArgBox();
		paramFields.put(null, offerBox);
		paramPanel.add(offerBox);
		paramPanel.add(new JLabel("Offer amount (0 or blank for no offer)"));

		add(paramPanel, BorderLayout.CENTER);

		// Actions for each operation
		JPanel actionPanel = new JPanel();
		actionPanel.setLayout(new FlowLayout(FlowLayout.LEFT));
		JButton execButton = new JButton("Execute...");
		actionPanel.add(execButton);
		execButton.addActionListener(e -> execute());

		add(actionPanel, BorderLayout.SOUTH);

	}

	private void execute() {

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
			Convex peerConnection = execPanel.getConvex();
			Address myAddress=peerConnection.getAddress();

			String mode = execPanel.getMode();
			Result r=null;
			if (mode.equals("Query")) {
				r=peerConnection.querySync(message);
			} else if (mode.equals("Transact")) {
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
			log.warn(e.getMessage());
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
