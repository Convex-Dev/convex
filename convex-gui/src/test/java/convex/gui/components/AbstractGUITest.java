package convex.gui.components;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.EventQueue;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.swing.JPanel;

import org.junit.jupiter.api.Test;

/**
 * Tests for the AbstractGUI lifecycle. These need no display: a GUI that has
 * never been shown closes through the same path as one whose window is closed.
 */
public class AbstractGUITest {

	@SuppressWarnings("serial")
	static class TestGUI extends AbstractGUI {
		TestGUI() {
			super("Test GUI");
		}
	}

	/** Records release events, marking any that happen on the EDT */
	static class Events {
		final List<String> list=Collections.synchronizedList(new ArrayList<>());

		void add(String name) {
			list.add(EventQueue.isDispatchThread()?name+"@EDT":name);
		}

		AutoCloseable resource(String name) {
			return ()->add(name);
		}
	}

	@SuppressWarnings("serial")
	static class CloseablePanel extends JPanel implements AutoCloseable {
		final Events events;
		final String name;

		CloseablePanel(Events events, String name) {
			this.events=events;
			this.name=name;
		}

		@Override
		public void close() {
			events.add(name);
		}
	}

	@Test
	public void testReleaseOrder() {
		Events events=new Events();
		TestGUI gui=new TestGUI();
		CloseablePanel outer=new CloseablePanel(events,"outer");
		JPanel plain=new JPanel();
		plain.add(new CloseablePanel(events,"inner"));
		outer.add(plain);
		gui.add(outer);
		gui.own(events.resource("first"));
		gui.own(events.resource("second"));

		gui.close();

		// Components children first, then owned resources most recent first, all off the EDT
		assertEquals(List.of("inner","outer","second","first"),events.list);
	}

	@Test
	public void testClosesOnce() {
		Events events=new Events();
		TestGUI gui=new TestGUI();
		gui.own(events.resource("r"));
		assertFalse(gui.isClosing());

		gui.close();
		gui.close();
		gui.closeGUI();
		gui.waitForClose();

		assertTrue(gui.isClosing());
		assertEquals(List.of("r"),events.list);
	}

	@Test
	public void testFailingResource() {
		Events events=new Events();
		TestGUI gui=new TestGUI();
		gui.own(events.resource("first"));
		gui.own(()->{throw new IllegalStateException("Deliberate test failure");});
		gui.own(events.resource("last"));

		gui.close();

		assertEquals(List.of("last","first"),events.list);
	}

	@Test
	public void testOwnAfterClose() {
		Events events=new Events();
		TestGUI gui=new TestGUI();
		gui.close();

		AutoCloseable r=events.resource("late");
		assertSame(r,gui.own(r));
		assertEquals(List.of("late"),events.list);
	}

	@Test
	public void testDisown() {
		Events events=new Events();
		TestGUI gui=new TestGUI();
		AutoCloseable r=gui.own(events.resource("r"));
		assertTrue(gui.disown(r));
		assertFalse(gui.disown(r));

		gui.close();

		assertEquals(List.of(),events.list);
	}

	@Test
	public void testAdoptedChildClosesWithParent() {
		Events events=new Events();
		TestGUI parent=new TestGUI();
		TestGUI child=parent.adopt(new TestGUI());
		child.own(events.resource("child"));

		parent.close();

		assertTrue(child.isClosing());
		assertEquals(List.of("child"),events.list);
	}

	@Test
	public void testAdoptedChildClosedFirst() {
		Events events=new Events();
		TestGUI parent=new TestGUI();
		TestGUI child=parent.adopt(new TestGUI());
		child.own(events.resource("child"));
		child.close();
		parent.own(events.resource("parent"));

		parent.close();

		assertEquals(List.of("child","parent"),events.list);
	}

	@Test
	public void testNestedGUIClosedWhole() {
		Events events=new Events();
		TestGUI outer=new TestGUI();
		TestGUI inner=new TestGUI();
		inner.add(new CloseablePanel(events,"panel"));
		outer.add(inner);

		outer.close();

		// The inner GUI releases its own components, so the panel closes once
		assertTrue(inner.isClosing());
		assertEquals(List.of("panel"),events.list);
	}

	@Test
	public void testNoWaitingOnDispatchThread() throws Exception {
		TestGUI gui=new TestGUI();
		EventQueue.invokeAndWait(()->{
			assertThrows(IllegalStateException.class, gui::close);
			assertThrows(IllegalStateException.class, gui::waitForClose);
		});
		assertFalse(gui.isClosing());
		gui.close();
	}
}
