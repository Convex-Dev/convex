package convex.gui.actor;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.EventQueue;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import convex.auth.did.DID;
import convex.core.cvm.AccountStatus;
import convex.core.cvm.Address;
import convex.core.cvm.State;
import convex.core.init.Init;
import convex.gui.models.StateModel;
import convex.lattice.ALatticeComponent;
import convex.lattice.ValueComponent;
import convex.lattice.cursor.Root;

public class AccountInfoPanelTest {

	@Test
	public void testKeyedAccountShowsDID() {
		AccountStatus account=AccountStatus.create(1000L,Init.DEFAULT_GENESIS_KEY);

		String info=AccountInfoPanel.getInfoText(Address.create(13),account);

		assertTrue(info.contains("Account Key:    "+Init.DEFAULT_GENESIS_KEY));
		assertTrue(info.contains("Key DID:        "+DID.forKey(Init.DEFAULT_GENESIS_KEY)));
	}

	@Test
	public void testActorDoesNotShowKeyDID() {
		String info=AccountInfoPanel.getInfoText(Address.create(14),AccountStatus.createActor());

		assertTrue(info.contains("Account Key:    null"));
		assertFalse(info.contains("Key DID:"));
	}

	/** A panel on a legacy state model and one on a domain component show the same text as the account changes */
	@Test void legacyModelAndDomainComponentUseTheSameView() throws Exception {
		var account=AccountStatus.create(1000L,Init.DEFAULT_GENESIS_KEY);
		var model=StateModel.create(State.EMPTY.putAccount(Address.ZERO,account));
		var stored=Root.create(account);
		var component=new ValueComponent<>(stored);
		Probe[] panels=new Probe[2];
		EventQueue.invokeAndWait(() -> {
			panels[0]=new Probe(model,Address.ZERO);
			panels[1]=new Probe(component,Address.ZERO);
			for (var panel:panels) panel.addNotify();
		});
		try {
			expectText(panels,getInfoText(account));
			var updated=account.withBalance(2000);
			model.setValue(State.EMPTY.putAccount(Address.ZERO,updated));
			stored.set(updated);
			expectText(panels,getInfoText(updated));
			model.setValue(State.EMPTY);
			stored.set((AccountStatus)null);
			expectText(panels,getInfoText(null));
		} finally {
			List<Executable> closes=new ArrayList<>();
			for (var panel:panels) closes.add(() -> EventQueue.invokeAndWait(() -> { panel.close(); panel.removeNotify(); }));
			assertAll("Close the panels",closes);
		}
	}

	private static String getInfoText(AccountStatus account) {
		return AccountInfoPanel.getInfoText(Address.ZERO,account);
	}

	/** Takes each panel's next render, which must show the expected text */
	private static void expectText(Probe[] panels,String expected) throws Exception {
		for (var panel:panels) {
			String text=panel.renders.poll(10,TimeUnit.SECONDS);
			assertNotNull(text,"Timed out waiting for view update");
			assertEquals(expected,text);
		}
	}

	/** Records the text each render leaves in the panel */
	@SuppressWarnings("serial")
	private static class Probe extends AccountInfoPanel {
		final BlockingQueue<String> renders=new LinkedBlockingQueue<>();
		Probe(StateModel<State> model,Address address) { super(model,address); }
		Probe(ALatticeComponent<AccountStatus> component,Address address) { super(component,address); }
		@Override protected void render(AccountStatus snapshot) {
			super.render(snapshot);
			renders.add(infoArea.getText());
		}
	}
}
