package convex.gui.wallet;

import javax.swing.JLabel;
import javax.swing.JPanel;

import convex.api.Convex;
import convex.core.Coin;
import convex.core.data.ACell;
import convex.core.data.prim.AInteger;
import convex.core.lang.RT;
import convex.gui.components.BalanceLabel;
import net.miginfocom.swing.MigLayout;

@SuppressWarnings("serial")
public class TokenInfoPanel extends JPanel {

	public TokenInfoPanel(Convex convex, TokenInfo token) {
		setLayout(new MigLayout("wrap 2"));
		
		add(new JLabel("Symbol: "));
		add(new JLabel(token.getSymbol()),"wrap");

		add(new JLabel("ID: "));
		ACell id=token.getID();
		String idString=RT.toString(id);
		add(new JLabel(idString),"wrap");

		add(new JLabel("Actor Name: "));
		JLabel descLabel=new JLabel("Loading...");
		add(descLabel,"wrap");
		
		add(new JLabel("Total Supply: "));
		BalanceLabel supplyLabel=new BalanceLabel();
		supplyLabel.setDecimals(0);
		supplyLabel.setAlignmentX(LEFT_ALIGNMENT);
		add(supplyLabel,"wrap");
		
		add(new JLabel("Decimals: "));
		JLabel decimalsLabel=new JLabel("Loading...");
		add(decimalsLabel,"wrap");
		
		if (id==null) {
			descLabel.setText("(Convex Gold: Native coin)");
			supplyLabel.setDecimals(Coin.DECIMALS);
			decimalsLabel.setText(Integer.toString(Coin.DECIMALS));
			convex.query("(coin-supply)").thenAcceptAsync(r->{
				if (r.isError()) {
					supplyLabel.setBalance(null);
				} else {
					supplyLabel.setBalance(RT.ensureInteger(r.getValue()));
				}
			});
		} else {
			convex.query("(get (call *registry* (lookup (address "+id+"))) :name)").thenAcceptAsync(r->{
				if (r.isError()) {
					descLabel.setText(r.getErrorCode().toString());
				} else {
					descLabel.setText(RT.toString(r.getValue()));
				}
			});
			
			convex.query("(@convex.asset/total-supply "+id+")").thenAcceptAsync(r->{
				if (r.isError()) {
					supplyLabel.setBalance(null);
				} else {
					supplyLabel.setBalance(RT.ensureInteger(r.getValue()));
				}
			});
			
			convex.query("(@convex.fungible/decimals "+id+")").thenAcceptAsync(r->{
				if (r.isError()) {
					decimalsLabel.setText("Error - "+r.getErrorCode());
					supplyLabel.setDecimals(0);
				} else {
					AInteger decs=RT.ensureInteger(r.getValue());
					supplyLabel.setDecimals((int)decs.longValue());
					decimalsLabel.setText(RT.toString(decs));
				}
			});
		}

	}

}
