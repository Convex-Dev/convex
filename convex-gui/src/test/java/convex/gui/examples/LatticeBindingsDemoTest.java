package convex.gui.examples;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.EventQueue;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JLabel;

import org.junit.jupiter.api.Test;

/** Exercises the complete example without opening a native window. */
public class LatticeBindingsDemoTest {
	@Test void draftAndPublishedDisplaysFollowTheirOwnBoundaries() throws Exception {
		AtomicReference<LatticeBindingsDemo> created=new AtomicReference<>();
		EventQueue.invokeAndWait(() -> created.set(new LatticeBindingsDemo() {
			private static final long serialVersionUID=1L;
			@Override public boolean isShowing() { return true; }
		}));
		try (var demo=created.get()) {
			awaitValues(demo,"0","0");
			EventQueue.invokeAndWait(() -> ((JButton)demo.getComponent(4)).doClick(0));
			awaitValues(demo,"0","1");
			EventQueue.invokeAndWait(() -> ((JButton)demo.getComponent(5)).doClick(0));
			awaitValues(demo,"1","1");
			EventQueue.invokeAndWait(() -> ((JButton)demo.getComponent(6)).doClick(0));
			awaitValues(demo,"2","1");
			EventQueue.invokeAndWait(() -> ((JButton)demo.getComponent(5)).doClick(0));
			awaitValues(demo,"2","2");

			// Optional headless preview for reviewing the runnable example's layout.
			String preview=System.getProperty("convex.gui.bindingsPreview");
			if (preview!=null) {
				AtomicReference<BufferedImage> image=new AtomicReference<>();
				EventQueue.invokeAndWait(() -> {
					demo.setSize(demo.getPreferredSize());
					demo.doLayout();
					var rendered=new BufferedImage(demo.getWidth(),demo.getHeight(),BufferedImage.TYPE_INT_RGB);
					var graphics=rendered.createGraphics();
					try { demo.printAll(graphics); }
					finally { graphics.dispose(); }
					image.set(rendered);
				});
				ImageIO.write(image.get(),"png",Path.of(preview).toFile());
			}
		}
	}

	private static void awaitValues(LatticeBindingsDemo demo,String published,String draft) throws Exception {
		long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
		AtomicReference<String> actual=new AtomicReference<>();
		String expected=published+","+draft;
		do {
			EventQueue.invokeAndWait(() -> actual.set(((JLabel)demo.getComponent(1)).getText()
				+","+((JLabel)demo.getComponent(3)).getText()));
			if (expected.equals(actual.get())) return;
			Thread.sleep(5);
		} while (System.nanoTime()<deadline);
		assertEquals(expected,actual.get());
	}
}
