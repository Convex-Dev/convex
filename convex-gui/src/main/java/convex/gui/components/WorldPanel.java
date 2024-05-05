package convex.gui.components;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Toolkit;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.awt.image.FilteredImageSource;
import java.awt.image.RGBImageFilter;
import java.io.IOException;

import javax.imageio.ImageIO;
import javax.swing.JPanel;

import convex.gui.utils.RobinsonProjection;

/**
 * Panel displaying world image an optional locations
 */
@SuppressWarnings("serial")
public class WorldPanel extends JPanel {
	static BufferedImage image;
	
	static {
		try {	
			BufferedImage base = ImageIO.read(Thread.currentThread().getContextClassLoader().getResource("images/world.png"));
			
			
			TintFilter filter=new TintFilter(0.1f,0.4f,0.7f);
			
			FilteredImageSource filteredImageSource = new FilteredImageSource(base.getSource(), filter ); 
			Image filteredImage = Toolkit.getDefaultToolkit().createImage(filteredImageSource);         

			BufferedImage img = new BufferedImage(base.getWidth(), base.getHeight(), BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = img.createGraphics(); 
			g.drawImage(filteredImage, 0, 0, null);
			g.dispose();
			image=img;
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public WorldPanel() {
		setBackground(Color.BLACK);
		setPreferredSize(new Dimension(image.getWidth(),image.getHeight()));
	}

	@Override
	public void paintComponent(Graphics g) {
		super.paintComponent(g);
		if (image == null) return;

		int w = this.getWidth();
		int h = this.getHeight();
		int sw = image.getWidth();
		int sh = image.getHeight();
		int dw = Math.min(w, h * sw / sh);
		int dh = Math.min(h, w * sh / sw);

		int y = (h - dh) / 2;
		int x = (w - dw) / 2;

		g.drawImage(image, x, y, x+dw, y + dh, 0, 0, sw, sh, null);

		paintDot(g, 51.5073219, -0.1276474, x, y, dw, dh); // London
		paintDot(g, -33.928992, 18.417396, x, y, dw, dh); // Cape Town
		paintDot(g, 35.6828387, 139.7594549, x, y, dw, dh); // Tokyo
		paintDot(g, 23.135305, -82.3589631, x, y, dw, dh); // Havana
	}

	private void paintDot(Graphics g, double latitude, double longitude, int x, int y, int dw, int dh) {
		g.setColor(Color.RED);
		Point2D pt = RobinsonProjection.getPoint(latitude, longitude);
		int px = (int) (x + dw * pt.getX());
		int py = (int) (y + dh * pt.getY());
		g.fillOval(px, py, 5, 5);
	}
	
	public static class TintFilter extends RGBImageFilter {
		private float rf;
		private float gf;
		private float bf;
		public TintFilter(float r, float g, float b) {
			this.rf=r;
			this.gf=g;
			this.bf=b;			
		}
		
		@Override
		public int filterRGB(int x, int y, int argb) {
	    	int r=(argb>>16)&0xff;
	    	int g=(argb>>8)&0xff;;
	    	int b=(argb>>0)&0xff;
	    	int a=argb&0xff000000;
	    	
	    	int nr=Math.max(0, Math.min(255, (int)(r*rf)));
	    	int ng=Math.max(0, Math.min(255, (int)(g*gf)));
	    	int nb=Math.max(0, Math.min(255, (int)(b*bf)));

			return a + (nr<<16)+(ng<<8)+nb;
	    }
	}
}
