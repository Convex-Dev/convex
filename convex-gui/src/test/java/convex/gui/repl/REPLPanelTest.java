package convex.gui.repl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.JButton;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;

import convex.api.ConvexDirect;
import convex.core.Result;
import convex.core.crypto.AKeyPair;
import convex.core.cvm.Address;
import convex.core.cvm.Migrations;
import convex.core.data.prim.CVMLong;
import convex.core.init.Init;
import convex.gui.utils.Toolkit;

public class REPLPanelTest {
	@Test
	public void testQueryModeWithZeroBalance() throws Exception {
		assumeFalse(GraphicsEnvironment.isHeadless());
		Toolkit.init();
		AKeyPair key=AKeyPair.generate();
		try (ConvexDirect client=ConvexDirect.create(key,
				Migrations.applyAll(Init.createTestState(List.of(key.getAccountKey()))))) {
			Address empty=client.createAccountSync(key.getAccountKey());
			client.setAddress(empty);
			client.setKeyPair(null); // Query mode requires no signing key.
			CompletableFuture<Result> result=new CompletableFuture<>();
			AtomicReference<REPLPanel> panel=new AtomicReference<>();
			try {
				SwingUtilities.invokeAndWait(()->{
					REPLPanel repl=new REPLPanel(client) {
						@Override protected void handleResult(long start, Result value) {
							result.complete(value);
						}
					};
					panel.set(repl);
					repl.setInput("*balance*");
					JButton run=findRunButton(repl);
					assertNotNull(run);
					run.doClick();
				});
				Result actual=result.get(5,TimeUnit.SECONDS);
				assertFalse(actual.isError(),actual::toString);
				assertEquals(CVMLong.ZERO,actual.getValue());
			} finally {
				// Stop the account chooser's refresh timer even without showing a window.
				if (panel.get()!=null) SwingUtilities.invokeAndWait(()->panel.get().removeNotify());
			}
		}
	}

	private JButton findRunButton(Container parent) {
		for (Component component: parent.getComponents()) {
			if (component instanceof JButton button && "Run".equals(button.getText())) return button;
			if (component instanceof Container container) {
				JButton button=findRunButton(container);
				if (button!=null) return button;
			}
		}
		return null;
	}
}
