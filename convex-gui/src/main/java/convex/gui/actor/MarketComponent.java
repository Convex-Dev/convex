package convex.gui.actor;

import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.text.DecimalFormat;
import java.util.HashMap;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;

import convex.core.cvm.State;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AVector;
import convex.core.cvm.Address;
import convex.core.data.Keyword;
import convex.core.data.Symbol;
import convex.core.data.prim.CVMLong;
import convex.core.cvm.Context;
import convex.core.lang.RT;
import convex.gui.components.BaseListComponent;
import convex.gui.components.CodeLabel;
import convex.gui.utils.Toolkit;

@SuppressWarnings("serial")
public class MarketComponent extends BaseListComponent {

	private Address address;
	AVector<ACell> outcomes;

	HashMap<Object, JLabel> probLabels = new HashMap<>(); // probabilities
	HashMap<Object, JLabel> tsLabels = new HashMap<>(); // total stake
	HashMap<Object, JLabel> osLabels = new HashMap<>(); // owned stake

	CodeLabel title;
	private int numOutcomes;
	private MarketsPanel marketsPanel;

	@SuppressWarnings("unchecked")
	public MarketComponent(MarketsPanel marketsPanel, Address addr) {
		this.marketsPanel = marketsPanel;
		this.address = addr;
		State state = marketsPanel.getLatestState();

		// prediction market data
		AMap<Symbol, ACell> pmEnv = state.getEnvironment(addr);
		outcomes = RT.keys(pmEnv.get(Symbol.create("totals")));

		numOutcomes = outcomes.size();

		// oracle data
		Address oracleAddress = (Address) pmEnv.get(Symbol.create("oracle"));
		if (oracleAddress == null) throw new Error("No oracle symbol in environment?");
		Object key = pmEnv.get(Symbol.create("oracle-key"));

		AMap<Symbol, ACell> oracleEnv = state.getEnvironment(oracleAddress);
		AMap<ACell, ACell> fullList = (AMap<ACell, ACell>) oracleEnv.get(Symbol.create("full-list"));
		AMap<Keyword, ACell> oracleData = (AMap<Keyword, ACell>) fullList.get(key);
		if (oracleData == null) throw new Error("No oracle data for key?");

		// Layout
		setLayout(new BorderLayout());

		// Top label
		String oName = RT.jvm( oracleData.get(Keyword.create("desc")));
		if (oName == null) oName = "Nameless Oracle";
		title = new CodeLabel(oName);
		title.setFont(Toolkit.MONO_FONT);
		add(title, BorderLayout.NORTH);

		// Centre panel
		JPanel jp = new JPanel();
		add(jp, BorderLayout.CENTER);

		jp.setLayout(new GridBagLayout());
		GridBagConstraints gbc = new GridBagConstraints();
		gbc.ipadx = 10;
		gbc.weightx = 1.0;
		gbc.weighty = 1.0;

		gbc.gridx = 0;
		jp.add(new JLabel("Outcome"));
		gbc.gridx = 1;
		jp.add(new JLabel("Probability"));
		gbc.gridx = 2;
		jp.add(new JLabel("Total Stake"));
		gbc.gridx = 3;
		jp.add(new JLabel("Owned Stake"));
		gbc.gridx = 4;
		jp.add(new JLabel("Actions"));
		for (int i = 0; i < numOutcomes; i++) {
			Object outcome = outcomes.get(i);

			gbc.gridy = 1 + i;
			gbc.gridx = 0;
			JLabel oLabel = new JLabel(outcome.toString() + "   ");
			oLabel.setHorizontalAlignment(SwingConstants.LEFT);
			jp.add(oLabel, gbc);

			gbc.gridx = 1;
			JLabel pLabel = new JLabel("??");
			jp.add(pLabel, gbc);
			probLabels.put(outcome, pLabel);

			gbc.gridx = 2;
			JLabel tsLabel = new JLabel("0");
			jp.add(tsLabel, gbc);
			tsLabels.put(outcome, tsLabel);

			gbc.gridx = 3;
			JLabel osLabel = new JLabel("0");
			jp.add(osLabel, gbc);
			osLabels.put(outcome, osLabel);

			gbc.gridx = 4;
			JPanel bp = new JPanel();
			bp.setLayout(new GridLayout(0, 2));
			JButton buyButton = new JButton("Buy");
			buyButton.addActionListener(e -> buy(outcome));
			bp.add(buyButton);
			JButton sellButton = new JButton("Sell");
			sellButton.addActionListener(e -> sell(outcome));
			bp.add(sellButton);
			jp.add(bp, gbc);
		}

		// Top label
		add(new CodeLabel("Market Address: " + address.toString()), BorderLayout.SOUTH);

		// state updates
		updateStatus(state);

		//marketsPanel.getStateModel().addPropertyChangeListener(e -> {
		//	State s = (State) e.getNewValue();
		//	updateStatus(s);
		//});

		marketsPanel.acctChooser.addressCombo.addActionListener(e -> {
			State s = marketsPanel.getLatestState();
			updateStatus(s);
		});
	}

	private void sell(Object outcome) {
		changeStake(outcome, -1000000);
	}

	private void buy(Object outcome) {
		changeStake(outcome, 1000000);
	}

	@SuppressWarnings("unused")
	private void changeStake(Object outcome, long delta) {
		// TODO: this is broken and needs fixing
		State state = marketsPanel.getLatestState();
		Long stk = getStake(state, outcome);
		if (stk == null) stk = 0L;
		long newStake = Math.max(0L, stk + delta);
		long offer = Math.max(0, delta); // covers stake increase for sure?

		//marketsPanel.execute(we, cmd).thenAcceptAsync(new DefaultReceiveAction(marketsPanel));
	}

	static DecimalFormat probFormatter = new DecimalFormat("0.0");

	private void updateStatus(State state) {
		try {
			Address caller = marketsPanel.acctChooser.getAddress();
			Context ctx = Context.create(state, caller); // fake for caller
			for (int i = 0; i < numOutcomes; i++) {
				ACell outcome = outcomes.get(i);

				double p = RT.jvm(ctx.actorCall(address, 0, "price", outcome).getResult());
				if (Double.isNaN(p)) p = 1.0 / numOutcomes;
				String prob = probFormatter.format(p * 100.0) + "%";
				probLabels.get(outcome).setText(prob);

				Long ts = RT.jvm( ctx.actorCall(address, 0, "totals", outcome).getResult());
				String totalStake = ts.toString();
				tsLabels.get(outcome).setText(totalStake);

				@SuppressWarnings("unchecked")
				AMap<Address, CVMLong> stks = (AMap<Address, CVMLong>) ctx.actorCall(address, 0, "stakes", outcome)
						.getResult();
				CVMLong stk = stks.get(caller);
				String ownStake = (stk == null) ? "0" : stk.toString();
				osLabels.get(outcome).setText(ownStake);
			}
		} catch (Throwable t) {
			t.printStackTrace();
		}
	}

	private Long getStake(State state, Object outcome) {
		Address caller = marketsPanel.acctChooser.getAddress();
		Context ctx = Context.create(state, caller);
		@SuppressWarnings("unchecked")
		AMap<Address, CVMLong> stks = (AMap<Address, CVMLong>) ctx.actorCall(address, 0, "stakes", RT.cvm(outcome)).getResult();
		return stks.get(caller).longValue();
	}

}
