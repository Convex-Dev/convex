package convex.gui.components;

import java.util.logging.Logger;

import javax.swing.JComponent;

import convex.core.lang.RT;
import convex.core.util.Utils;
import convex.net.Message;
import convex.net.ResultConsumer;

public class DefaultReceiveAction extends ResultConsumer {

	public static final Logger log = Logger.getLogger(DefaultReceiveAction.class.getName());

	private JComponent parent;

	public DefaultReceiveAction(JComponent parent) {
		this.parent = parent;
	}

	@Override
	protected void handleResult(Object m) {
		showResult(m);
	}

	@Override
	protected void handleError(Message m) {
		showError(m);
	}

	private void showError(Message m) {
		Object em;
		try {
			em = RT.nth(m.getPayload(), 1);
		} catch (Exception e) {
			em = e.getMessage();
		}
		String resultString = "Error executing transaction: " + em;
		log.info(resultString);
		Toast.display(parent, resultString, Toast.FAIL);
	}

	private void showResult(Object v) {
		String resultString = "Transaction executed successfully\n" + "Result: " + Utils.toString(v);
		log.info(resultString);
		Toast.display(parent, resultString, Toast.SUCCESS);
	}
}
