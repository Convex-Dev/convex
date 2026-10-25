package convex.gui.utils;

import javax.swing.JComponent;
import javax.swing.UIDefaults;
import javax.swing.UIManager;
import javax.swing.plaf.ComponentUI;

/**
 * UI delegate factory that makes Swing show component text literally.
 *
 * <p>Swing renders label, button, tooltip and cell renderer text starting with
 * {@code <html>} as HTML, and {@code JOptionPane} renders any message containing
 * it. The GUI shows text written by other parties (registry names, actor error
 * messages, DLFS file names), and HTML can fetch remote resources and restyle
 * what the user sees. So HTML is off for every component unless it opts in.
 *
 * <p>Swing has no global switch: {@code BasicHTML} only checks each component's
 * {@code html.disable} client property. {@link #install()} registers this class
 * as the factory for every UI class ID of the installed look and feel, and
 * {@link #createUI(JComponent)} sets the property before the look and feel's own
 * delegate is installed, so even text passed to a constructor shows literally.
 * Registrations are developer defaults, which survive look and feel changes; the
 * delegate comes from the look and feel's own defaults table, which never sees
 * them, so the lookup does not recurse.
 *
 * <p>To render deliberate HTML, opt the component back in and reinstall its UI:
 * {@code c.putClientProperty(PlainTextUI.HTML_DISABLE, Boolean.FALSE); c.updateUI();}
 */
public class PlainTextUI {

	/** Client property read by {@code BasicHTML}; not public in Swing */
	public static final String HTML_DISABLE="html.disable";

	/**
	 * Disables HTML on a component unless it has opted in, then creates the
	 * current look and feel's delegate for it. Called by Swing via {@link UIManager}.
	 * @param c Component needing a UI delegate
	 * @return Look and feel delegate for the component
	 */
	public static ComponentUI createUI(JComponent c) {
		if (c.getClientProperty(HTML_DISABLE)==null) c.putClientProperty(HTML_DISABLE, Boolean.TRUE);
		return UIManager.getLookAndFeelDefaults().getUI(c);
	}

	/**
	 * Registers this factory for every UI class ID of the installed look and feel.
	 * Components created earlier keep their existing delegates.
	 */
	public static void install() {
		UIDefaults laf=UIManager.getLookAndFeelDefaults();
		for (Object key: laf.keySet().toArray()) {
			if ((key instanceof String id)&&id.endsWith("UI")&&(id.indexOf('.')<0)&&(laf.get(id) instanceof String)) {
				UIManager.put(id, PlainTextUI.class.getName());
			}
		}
	}
}
