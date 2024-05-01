package convex.gui.server;

import java.util.HashMap;

import javax.swing.ButtonGroup;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;

import convex.observer.LogObserver;
import convex.observer.StrimziKafka;
import convex.peer.Server;
import convex.peer.TransactionHandler;
import net.miginfocom.swing.MigLayout;

@SuppressWarnings("serial")
public class ObserverPanel extends JPanel {

	protected Server server;

	/**
	 * Create the panel.
	 * @param server Local server instance
	 */
	public ObserverPanel(Server server) {
		this.server=server;
		
		this.setLayout(new MigLayout("wrap 2"));
		
		add(new JLabel("Transactions"),"span 2");
		JRadioButton noneButton=addButton("Transactions",new JLabel("None"),()->{
			server.getTransactionHandler().setRequestObserver(null);
			server.getTransactionHandler().setResponseObserver(null);
		});
		noneButton.setSelected(true);
		add(noneButton);
		add(new JLabel("None"));
		
		JRadioButton strmButton=addButton("Transactions",new JLabel("Strimzi"),()->{
			StrimziKafka obs=StrimziKafka.get(server);
			TransactionHandler th=server.getTransactionHandler();
			th.setRequestObserver(obs.getTransactionRequestObserver(server));
			th.setResponseObserver(obs.getTransactionResponseObserver(server));
		});
		add(strmButton);
		add(new JLabel("Strimzi"));	
		
		JRadioButton tlogButton=addButton("Transactions",new JLabel("Logs"),()->{
			TransactionHandler th=server.getTransactionHandler();
			LogObserver ob=new LogObserver(server);
			th.setRequestObserver(ob.getTransactionRequestObserver());
			th.setResponseObserver(ob.getTransactionResponseObserver());
		});
		add(tlogButton);
		add(new JLabel("SLF4J Logging"));	

		
	}

	private JRadioButton addButton(String bgName,JLabel jLabel, Runnable action) {
		ButtonGroup buttonGroup=getGroup(bgName);
		JRadioButton button=new JRadioButton();
		buttonGroup.add(button);
		button.addActionListener(e->{
			action.run();
		});
		return button;
	}

	private HashMap<String,ButtonGroup> bgroups=new HashMap<>();
	private ButtonGroup getGroup(String bgName) {
		ButtonGroup bg=bgroups.get(bgName);
		if (bg==null) {
			bg=new ButtonGroup();
			bgroups.put(bgName, bg);
		}
		return bg;
	}
}
