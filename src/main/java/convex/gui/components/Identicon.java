package convex.gui.components;

import java.awt.image.BufferedImage;

import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.border.BevelBorder;

import convex.core.data.ABlob;
import convex.core.util.Utils;
import convex.gui.manager.Toolkit;

/**
 * A simple identicon for visualising hash values.
 */
@SuppressWarnings("serial")
public class Identicon extends JLabel {

	public static BufferedImage createImage(ABlob data, int renderSize) {
		int SIZE = 3;
		byte[] bs = data.getBytes();

		BufferedImage bi = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);

		for (int y = 0; y < SIZE; y++) {
			for (int x = 0; x < SIZE; x++) {
				int i = x + y * SIZE;
				int bits = Utils.extractBits(bs, 9, 9 * i); // take 3 bits per channel
				int rgb = ((bits & 0b111000000) << 15) + ((bits & 0b111000) << 10) + ((bits & 0b111) << 5);
				bi.setRGB(x, y, rgb);
			}
		}

		return Toolkit.smoothResize(bi, renderSize, renderSize);
	}

	public Identicon(ABlob a) {
		super(new ImageIcon(Identicon.createImage(a, 36)));

		setBorder(new BevelBorder(BevelBorder.RAISED, null, null, null, null));
	}
}
