package convex.gui.components;

import java.text.DecimalFormat;

import javax.swing.JFormattedTextField;
import javax.swing.SwingConstants;

import convex.core.text.Text;

@SuppressWarnings("serial")
public class DecimalAmountField extends JFormattedTextField {

	public DecimalAmountField(int decimals) {
		super(getNumberFormat(decimals));
		this.setHorizontalAlignment(SwingConstants.RIGHT);
		setColumns(20);
	}
	
	public static DecimalFormat getNumberFormat(int decimals) {
		return new DecimalFormat("#,###"+((decimals>0)?("."+Text.repeat('#',decimals)):""));
		
	}
 }
