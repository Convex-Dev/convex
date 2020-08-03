package convex.gui.components;

import java.util.logging.Logger;

import javax.swing.JComponent;

import convex.core.util.Utils;
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
	protected void handleError(long id, Object code, Object msg) {
		showError(code,msg);
	}

	private void showError(Object code, Object msg) {
		String resultString = "Error executing transaction: " + code + " "+msg; 
		log.info(resultString);
		Toast.display(parent, resultString, Toast.FAIL);
	}

	private void showResult(Object v) {
		String resultString = "Transaction executed successfully\n" + "Result: " + Utils.toString(v);
		log.info(resultString);
		Toast.display(parent, resultString, Toast.SUCCESS);
	}
}
