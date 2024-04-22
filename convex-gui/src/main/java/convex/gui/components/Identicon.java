package convex.gui.components;

import java.awt.FlowLayout;
import java.awt.image.BufferedImage;
import java.util.WeakHashMap;

import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.border.BevelBorder;

import convex.core.data.AArrayBlob;
import convex.core.data.Blobs;
import convex.core.data.Hash;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.Utils;
import convex.gui.utils.Toolkit;

/**
 * A simple identicon for visualising hash values.
 */
@SuppressWarnings("serial")
public class Identicon extends JLabel {

	protected final int size;
	
	public BufferedImage createImage(AArrayBlob data, int renderSize) {
		int SIZE = size;
		if (data==null) return  new BufferedImage(renderSize, renderSize, BufferedImage.TYPE_INT_RGB);
		
		BufferedImage bi = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
		byte[] bs = Blobs.ensureZeroBasedArray(data);

		int[] cols=new int[4];
		
		// last 12 bytes define colours, 3 bytes per colour, we mask the first byte for ARGB
		for (int i=0; i<4; i++) {
			int col=0xFF000000|(Utils.readInt(bs, 20+i*3-1));
			cols[i]=col;
		}
		
		// First 20 bytes define bitmap. Take 2 bits for 
		for (int y = 0; y < SIZE; y++) {
			for (int x = 0; x <= SIZE/2; x++) {
				int i = (x + y * (1+SIZE/2))%(20*4); // 4 2-byte segments per byte
				int bits = Utils.extractBits(bs, 2, 2 * i); // take 2 bits for colour index
				int rgb = cols[bits];
				bi.setRGB(x, y, rgb);
				bi.setRGB(SIZE-x-1, y, rgb);
			}
		}

		return Toolkit.pixelResize(bi, renderSize, renderSize); 
	}
	
	/**
	 * A weak cache for Icons
	 */
	private WeakHashMap<AArrayBlob,ImageIcon> wm=new WeakHashMap<>();
	
	private ImageIcon createIcon(AArrayBlob hash, int size) {
		ImageIcon cached=wm.get(hash);
		if ((cached!=null)&&(cached.getImage().getWidth(null)==size)) return cached;
		
		ImageIcon result=new ImageIcon(createImage(hash,size));
		result.setDescription(RT.toString(hash));
		wm.put(hash, result);
		return result;
	}

	public Identicon(AArrayBlob a) {
		super();
		if ((a!=null)&&a.count()<32) throw new IllegalArgumentException("Blob too short for Identicon, must be 32+ bytes. Maybe use a Hash?");
		size=7;
		ImageIcon icon=createIcon(a, 36);
		setIcon(icon);
		this.setToolTipText(icon.getDescription());
		setBorder(new BevelBorder(BevelBorder.RAISED, null, null, null, null));
	}

	public static class IdenticApp extends AbstractGUI{
		public IdenticApp() {
			setLayout(new FlowLayout());
			for (int i=0; i<256; i++) {
				add(new Identicon(Hash.compute(CVMLong.create(i).getHash())));
			}
			add(new Identicon(null));
		}
	}
	
	public static void main(String... args) {
		Toolkit.init();
		IdenticApp app=new IdenticApp();
		app.run();
	}
}
