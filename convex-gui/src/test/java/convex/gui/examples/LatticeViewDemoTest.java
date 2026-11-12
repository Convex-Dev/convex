package convex.gui.examples;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.EventQueue;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.imageio.ImageIO;
import javax.swing.JButton;

import org.junit.jupiter.api.Test;

/** Exercises the complete example without opening a native window. */
public class LatticeViewDemoTest {
	@Test void draftAndPublishedDisplaysFollowTheirOwnBoundaries() throws Exception {
		AtomicReference<LatticeViewDemo> created=new AtomicReference<>();
		EventQueue.invokeAndWait(() -> {
			var demo=new LatticeViewDemo();
			created.set(demo);
			demo.addNotify(); // Lightweight peers allow visibility lifecycle tests without a window.
		});
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
			String preview=System.getProperty("convex.gui.latticeViewPreview");
			if (preview!=null) {
				AtomicReference<BufferedImage> image=new AtomicReference<>();
				EventQueue.invokeAndWait(() -> {
					demo.setSize(demo.getPreferredSize());
					demo.doLayout();
					((LatticeViewDemo.CounterPanel)demo.getComponent(1)).doLayout();
					((LatticeViewDemo.CounterPanel)demo.getComponent(3)).doLayout();
					var rendered=new BufferedImage(demo.getWidth(),demo.getHeight(),BufferedImage.TYPE_INT_RGB);
					var graphics=rendered.createGraphics();
					try { demo.printAll(graphics); }
					finally { graphics.dispose(); }
					image.set(rendered);
				});
				ImageIO.write(image.get(),"png",Path.of(preview).toFile());
			}
		} finally {
			EventQueue.invokeAndWait(() -> created.get().removeNotify());
		}
	}

	private static void awaitValues(LatticeViewDemo demo,String published,String draft) throws Exception {
		long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
		AtomicReference<String> actual=new AtomicReference<>();
		String expected=published+","+draft;
		do {
			EventQueue.invokeAndWait(() -> {
				var publishedView=(LatticeViewDemo.CounterPanel)demo.getComponent(1);
				var draftView=(LatticeViewDemo.CounterPanel)demo.getComponent(3);
				actual.set(publishedView.value.getText()+","+draftView.value.getText());
				if (expected.equals(actual.get())) {
					assertEquals("Next: "+(Integer.parseInt(published)+1),publishedView.next.getText());
					assertEquals("Next: "+(Integer.parseInt(draft)+1),draftView.next.getText());
				}
			});
			if (expected.equals(actual.get())) return;
			Thread.sleep(5);
		} while (System.nanoTime()<deadline);
		assertEquals(expected,actual.get());
	}
}
