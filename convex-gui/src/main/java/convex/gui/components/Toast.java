package convex.gui.components;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Point;
import java.awt.Toolkit;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.geom.RoundRectangle2D;

import javax.swing.JComponent;
import javax.swing.JTextArea;
import javax.swing.JWindow;

/**
 * A simple class for implementing a "Toast" style notification.
 */
@SuppressWarnings("serial")
public class Toast extends JWindow {

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

	private void doDisplay(final long millis) {
		setVisible(true);
		long start=System.currentTimeMillis();
		new Thread(()->{
			try {
				long time=start;
				while (time<(start+millis)) {
					Thread.sleep(100);
					time=System.currentTimeMillis();
					// drop opacity after 50% of time has elapsed
					double opac=Math.min(1.0,Math.max(0.0,(2*(1.0-(time-start)/(double)millis))));
					setOpacity((float)opac) ;
				}
			} catch (InterruptedException e) {
				// set interrupted flag, clear toast and return
				setOpacity(0.0f);
				Thread.currentThread().interrupt();
			} finally  {
				setVisible(false);
			}
		}).start();;
	}
	
	public static void display(JComponent parent, JComponent component, Color colour) {
		Toast toast=new Toast(parent,component,colour);
		toast.doDisplay(Toast.DEFAULT_TIME);
	}
	
	public static void display(JComponent parent, JComponent component) {
		display(parent,component,SUCCESS);
	}

	public static void display(JComponent parent, String message, Color colour) {
		JTextArea ta=new JTextArea(message);
		ta.setBackground(null);
		ta.setEditable(false);
		display(parent,ta,colour);
	}
}
