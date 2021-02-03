package convex.gui.components;

import java.util.function.Consumer;
import java.util.logging.Logger;

import javax.swing.JComponent;

import convex.core.Result;
import convex.core.lang.RT;
import convex.core.util.Utils;

public class DefaultReceiveAction implements Consumer<Result> {

	public static final Logger log = Logger.getLogger(DefaultReceiveAction.class.getName());

	private JComponent parent;

	public DefaultReceiveAction(JComponent parent) {
		this.parent = parent;
	}
	
	@Override
	public void accept(Result t) {
		if (t.isError()) {
			handleError(RT.jvm(t.getID()),t.getErrorCode(),t.getValue());
		} else {
			handleResult(t.getValue());
		}
	}

	protected void handleResult(Object m) {
		showResult(m);
	}

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
