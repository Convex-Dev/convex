package convex.gui.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.awt.Component;
import java.awt.Container;
import java.awt.EventQueue;
import java.awt.GraphicsEnvironment;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JTable;
import javax.swing.JToolTip;
import javax.swing.JTree;
import javax.swing.UIManager;
import javax.swing.plaf.basic.BasicHTML;
import javax.swing.table.DefaultTableModel;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import convex.core.data.Strings;
import convex.gui.components.renderer.CellRenderer;
import convex.gui.dlfs.DLFSTreeCellRenderer;
import convex.gui.dlfs.DirectoryTree;
import convex.gui.dlfs.FileList;
import convex.lattice.fs.DLFS;
import convex.lattice.fs.DLFileSystem;

/**
 * Tests that text from other parties shows literally rather than as HTML (#765).
 * A component renders its text as HTML exactly when Swing has installed an HTML
 * view in its {@link BasicHTML#propertyKey} client property.
 */
public class PlainTextUITest {

	/** Text Swing would render as HTML, without '/' so it is also a valid DLFS file name */
	static final String HTML="<html><b>bold";

	@BeforeAll
	public static void init() {
		Toolkit.init();
	}

	/**
	 * Asserts a component shows the given text, literally
	 * @param text Text the component shows
	 * @param c Component
	 */
	static void assertLiteral(String text, JComponent c) {
		assertEquals(HTML,text);
		assertFalse(c.getClientProperty(BasicHTML.propertyKey)!=null,"Rendered as HTML");
	}

	/** Runs Swing code on the EDT, rethrowing assertion failures */
	static void onEDT(Runnable r) throws Exception {
		try {
			EventQueue.invokeAndWait(r);
		} catch (InvocationTargetException e) {
			if (e.getCause() instanceof Error err) throw err;
			throw e;
		}
	}

	@Test
	public void testLabel() throws Exception {
		onEDT(()->{
			JLabel constructed=new JLabel(HTML);
			assertLiteral(constructed.getText(),constructed);

			JLabel set=new JLabel();
			set.setText(HTML);
			assertLiteral(set.getText(),set);

			// Still the look and feel's own delegate
			assertEquals(UIManager.getLookAndFeelDefaults().getUIClass("LabelUI"),constructed.getUI().getClass());
		});
	}

	@Test
	public void testOptIn() throws Exception {
		onEDT(()->{
			JLabel label=new JLabel(HTML);
			label.putClientProperty(PlainTextUI.HTML_DISABLE, Boolean.FALSE);
			label.updateUI();
			assertTrue(label.getClientProperty(BasicHTML.propertyKey)!=null);
		});
	}

	@Test
	public void testButtonAndToolTip() throws Exception {
		onEDT(()->{
			JButton button=new JButton(HTML);
			assertLiteral(button.getText(),button);

			JToolTip tip=new JLabel("Token").createToolTip();
			tip.setTipText(HTML);
			assertLiteral(tip.getTipText(),tip);
		});
	}

	@Test
	public void testTableRenderers() throws Exception {
		onEDT(()->{
			JTable table=new JTable(new DefaultTableModel(new Object[][] {{HTML}},new Object[] {"Name"}));

			// Registry names in the accounts table are CVM strings
			JLabel c=(JLabel)new CellRenderer(JLabel.LEFT).getTableCellRendererComponent(table, Strings.create(HTML), false, false, 0, 0);
			assertLiteral(c.getText(),c);

			// Swing's own default renderer, used by tables without a custom one
			JLabel d=(JLabel)table.prepareRenderer(table.getCellRenderer(0, 0), 0, 0);
			assertLiteral(d.getText(),d);
		});
	}

	@Test
	public void testDLFSTreeRenderer() throws Exception {
		try (DLFileSystem fs=DLFS.createLocal()) {
			Path p=fs.getPath("/"+HTML);
			onEDT(()->{
				JLabel t=(JLabel)new DLFSTreeCellRenderer().getTreeCellRendererComponent(new JTree(), new DirectoryTree.Node(p), false, false, true, 0, false);
				assertLiteral(t.getText(),t);
			});
		}
	}

	@Test
	public void testDLFSFileListRenderer() throws Exception {
		// FileList enables drag and drop, which needs a display
		assumeFalse(GraphicsEnvironment.isHeadless());
		try (DLFileSystem fs=DLFS.createLocal()) {
			Path p=fs.getPath("/"+HTML);
			onEDT(()->{
				FileList list=new FileList(fs.getPath("/"),dir->{});
				JLabel c=(JLabel)list.getCellRenderer().getListCellRendererComponent(new JList<Path>(), p, 0, false, false);
				assertLiteral(c.getText(),c);
			});
		}
	}

	@Test
	public void testOptionPaneMessage() throws Exception {
		onEDT(()->{
			// JOptionPane gives the <html>...</html> part of a message a label of its own
			JOptionPane pane=new JOptionPane("Transfer failed!\n"+HTML+"</html>");
			List<JLabel> htmlLabels=new ArrayList<>();
			for (JLabel label: labels(pane)) {
				if (BasicHTML.isHTMLString(label.getText())) htmlLabels.add(label);
			}
			assertEquals(1,htmlLabels.size());
			assertFalse(htmlLabels.get(0).getClientProperty(BasicHTML.propertyKey)!=null,"Rendered as HTML");
		});
	}

	static List<JLabel> labels(Container c) {
		List<JLabel> result=new ArrayList<>();
		for (Component child: c.getComponents()) {
			if (child instanceof JLabel label) result.add(label);
			if (child instanceof Container cc) result.addAll(labels(cc));
		}
		return result;
	}
}
