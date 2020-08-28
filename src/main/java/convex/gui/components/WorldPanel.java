package convex.gui.components;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.IOException;

import javax.imageio.ImageIO;
import javax.swing.JPanel;

import convex.gui.utils.RobinsonProjection;

@SuppressWarnings("serial")
public class WorldPanel extends JPanel {
	BufferedImage image;

	public WorldPanel() {
		try {
			image = ImageIO.read(Thread.currentThread().getContextClassLoader().getResource("images/world.png"));
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	@Override
	public void paintComponent(Graphics g) {
		if (image == null) return;

		int w = this.getWidth();
		int h = this.getHeight();
		int sw = image.getWidth();
		int sh = image.getHeight();
		int dw = Math.min(w, h * sw / sh);
		int dh = Math.min(h, w * sh / sw);

		int y = (h - dh) / 2;

		g.drawImage(image, 0, y, dw, y + dh, 0, 0, sw, sh, null);

		paintDot(g, 51.5073219, -0.1276474, 0, y, dw, dh); // London
		paintDot(g, -33.928992, 18.417396, 0, y, dw, dh); // Cape Town
		paintDot(g, 35.6828387, 139.7594549, 0, y, dw, dh); // Tokyo
		paintDot(g, 23.135305, -82.3589631, 0, y, dw, dh); // Havana
	}

	private void paintDot(Graphics g, double latitude, double longitude, int x, int y, int dw, int dh) {
		g.setColor(Color.RED);
		Point2D pt = RobinsonProjection.getPoint(latitude, longitude);
		int px = (int) (x + dw * pt.getX());
		int py = (int) (y + dh * pt.getY());
		g.fillOval(px, py, 5, 5);
	}
}
