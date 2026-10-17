package convex.gui.components;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.Graphics;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Toolkit;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.geom.RoundRectangle2D;

import javax.swing.JComponent;
import javax.swing.JTextArea;
import javax.swing.JWindow;
import javax.swing.Timer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A simple class for implementing a "Toast" style notification.
 */
@SuppressWarnings("serial")
public class Toast extends JWindow {

	private static final Logger log = LoggerFactory.getLogger(Toast.class);

	public static final Color SUCCESS = new Color(100,150,0);
	public static final Color FAIL = new Color(150,50,50);
	public static final Color INFO = new Color(50,100,150);
	public static final long DEFAULT_TIME = 4000;

	public Toast(JComponent parent, JComponent component, Color color) {
		super();
		this.setLayout(new BorderLayout());
		this.setBackground(color);
		this.getContentPane().setBackground(color);
		add(component);
		
		if (parent!=null) {
			Point pp=parent.getLocationOnScreen();
			
			int px=(int) pp.getX();
			int py=(int) pp.getY();
			int pw=parent.getWidth();
			int ph=parent.getHeight();
			int h=50;
			setLocation(px, py+ph-h);
			setSize(pw,h);
		} else {
			Dimension dims=Toolkit.getDefaultToolkit().getScreenSize();
			int cx=dims.width/2;
			int cy=dims.height/2;
			int w=600;
			int h=100;
			setLocation(cx-w/2, cy+w/2);
			setSize(w,h);
		}
		
		addComponentListener(new ComponentAdapter() {
			@Override
			public void componentResized(ComponentEvent e) {
				setShape(new RoundRectangle2D.Float(0, 0,getWidth(), getHeight(), 16, 16));
			}
		});
	}
	
	@Override
	public void paint(Graphics g) {
		super.paint(g);
	}

	/** Shows the toast, fading out over its last second, then disposes it. Runs on the EDT. */
	private void doDisplay(final long millis) {
		setVisible(true);
		long start=System.currentTimeMillis();
		Timer fade=new Timer(50, null);
		fade.addActionListener(e->{
			long remaining=millis-(System.currentTimeMillis()-start);
			if (remaining<=0) {
				fade.stop();
				dispose();
			} else {
				setOpacity((float)Math.min(1.0, remaining/1000.0));
			}
		});
		fade.start();
	}

	/**
	 * Displays a toast. Safe to call from any thread.
	 * @param parent Component to show the toast over, or null to centre on screen
	 * @param component Content of the toast
	 * @param colour Background colour
	 */
	public static void display(JComponent parent, JComponent component, Color colour) {
		if (GraphicsEnvironment.isHeadless()) return;
		if (!EventQueue.isDispatchThread()) {
			EventQueue.invokeLater(()->display(parent,component,colour));
			return;
		}
		Toast toast=new Toast(parent,component,colour);
		toast.doDisplay(Toast.DEFAULT_TIME);
	}
	
	public static void display(JComponent parent, JComponent component) {
		display(parent,component,SUCCESS);
	}

	public static void display(JComponent parent, String message, Color colour) {
		if (GraphicsEnvironment.isHeadless()) {
			// No window to show it in, e.g. headless tests
			log.info("Toast: {}",message);
			return;
		}
		if (!EventQueue.isDispatchThread()) {
			EventQueue.invokeLater(()->display(parent,message,colour));
			return;
		}
		JTextArea ta=new JTextArea(message);
		ta.setBackground(null);
		ta.setForeground(Color.BLACK);
		ta.setFont(convex.gui.utils.Toolkit.BIG_FONT);
		ta.setEditable(false);
		display(parent,ta,colour);
	}
}
