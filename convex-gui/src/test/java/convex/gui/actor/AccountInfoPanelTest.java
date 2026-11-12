package convex.gui.actor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.EventQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import convex.auth.did.DID;
import convex.core.cvm.AccountStatus;
import convex.core.cvm.Address;
import convex.core.cvm.State;
import convex.core.init.Init;
import convex.gui.models.StateModel;
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

	@Test void legacyModelAndDomainComponentUseTheSameView() throws Exception {
		var account=AccountStatus.create(1000L,Init.DEFAULT_GENESIS_KEY);
		var model=StateModel.create(State.EMPTY.putAccount(Address.ZERO,account));
		var component=new ValueComponent<>(Root.create(account));
		AccountInfoPanel[] panels=new AccountInfoPanel[2];
		EventQueue.invokeAndWait(() -> {
			panels[0]=new AccountInfoPanel(model,Address.ZERO);
			panels[1]=new AccountInfoPanel(component,Address.ZERO);
			for (var panel:panels) panel.addNotify();
		});
		try {
			awaitText(panels,getInfoText(account));
			var updated=account.withBalance(2000);
			model.setValue(State.EMPTY.putAccount(Address.ZERO,updated));
			component.cursor().set(updated);
			awaitText(panels,getInfoText(updated));
			model.setValue(State.EMPTY);
			component.cursor().set((AccountStatus)null);
			awaitText(panels,getInfoText(null));
		} finally {
			EventQueue.invokeAndWait(() -> {
				for (var panel:panels) { panel.close(); panel.removeNotify(); }
			});
		}
	}

	private static String getInfoText(AccountStatus account) {
		return AccountInfoPanel.getInfoText(Address.ZERO,account);
	}

	private static void awaitText(AccountInfoPanel[] panels,String expected) throws Exception {
		long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
		AtomicReference<String> actual=new AtomicReference<>();
		do {
			EventQueue.invokeAndWait(() -> {
				actual.set(expected);
				for (var panel:panels) {
					String text=panel.infoArea.getText();
					if (!expected.equals(text)) actual.set(text);
				}
			});
			if (expected.equals(actual.get())) return;
			Thread.sleep(5);
		} while (System.nanoTime()<deadline);
		assertEquals(expected,actual.get());
	}
}
