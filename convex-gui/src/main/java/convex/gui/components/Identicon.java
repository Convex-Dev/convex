package convex.gui.components;

import java.awt.FlowLayout;
import java.awt.image.BufferedImage;
import java.util.WeakHashMap;

import javax.swing.ImageIcon;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.border.BevelBorder;

import convex.core.crypto.IdenticonBuilder;
import convex.core.data.AArrayBlob;
import convex.core.data.Blobs;
import convex.core.data.Hash;
import convex.core.data.Keyword;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.gui.utils.Toolkit;
import net.miginfocom.swing.MigLayout;

/**
 * A simple identicon component for visualising hash values.
 */
@SuppressWarnings("serial")
public class Identicon extends JLabel {
	
	protected int displaySize=Toolkit.IDENTICON_SIZE;

	protected static final int SIZE=IdenticonBuilder.SIZE;
	
	public static BufferedImage createImage(AArrayBlob data, int renderSize) {
		if ((data==null)||(data.isEmpty())) return  new BufferedImage(renderSize, renderSize, BufferedImage.TYPE_INT_RGB);
		
		int[] pixData=IdenticonBuilder.build(data);
		
		BufferedImage bi = new BufferedImage(SIZE,SIZE,BufferedImage.TYPE_INT_RGB);
		bi.setRGB(0, 0, SIZE, SIZE, pixData, 0, SIZE);

		return Toolkit.pixelResize(bi, renderSize, renderSize); 
	}


	
	/**
	 * A weak cache for Icons
	 */
	private static WeakHashMap<AArrayBlob,ImageIcon> wm=new WeakHashMap<>();
	
	public static ImageIcon createIcon(AArrayBlob hash, int size) {
		ImageIcon cached=wm.get(hash);
		if ((cached!=null)&&(cached.getImage().getWidth(null)==size)) return cached;
		
		ImageIcon result=new ImageIcon(createImage(hash,size));
		result.setDescription(RT.toString(hash));
		wm.put(hash, result);
		return result;
	}
	
	public Identicon(AArrayBlob a, int displaySize) {
		super();
		this.displaySize=displaySize;
		setKey(a);
		setFont(Toolkit.MONO_FONT);
		setBorder(new BevelBorder(BevelBorder.RAISED, null, null, null, null));
		
		Toolkit.addPopupMenu(this,new JPopupMenu() {
			{
				JMenuItem closeButton = new JMenuItem("Copy Public Key",Toolkit.menuIcon(0xe14d));
				closeButton.addActionListener(e -> {
					Toolkit.copyToClipboard(a.toString());
				});
				add(closeButton);
			}
		});
	}

	public Identicon(AArrayBlob a) {
		this(a,Toolkit.IDENTICON_SIZE);
	}

	public static class IdenticApp extends AbstractGUI{
		public IdenticApp() {
			super ("Convex Identicons");
			setLayout(new FlowLayout());
			
			// Identicons for Strings
			for (int i=0; i<64; i++) {
				add(new Identicon(CVMLong.create(i).getHash().toCVMString(100).toFlatBlob()));
			}
			
			// Identicons for small encodings
			for (int i=0; i<64; i++) {
				add(new Identicon(Keyword.create(Integer.toString(i)).getEncoding()));
			}
			
			// Identicons for blobs with same values
			for (int i=0; i<64; i++) {
				add(new Identicon(Blobs.createFilled(i*4, 32).toFlatBlob()));
			}

			// Identicons for Hashes / random data
			for (int i=0; i<64; i++) {
				add(new Identicon(Hash.get(CVMLong.create(i).getHash())));
			}
			add(new Identicon(null));
		}

		@Override
		public void setupFrame(JFrame frame) {
			frame.getContentPane().setLayout(new MigLayout());
			frame.getContentPane().add(this,"dock center");
		}
	}
	
	public static void main(String... args) {
		Toolkit.init();
		IdenticApp app=new IdenticApp();
		app.run();
	}

	public void setKey(AArrayBlob a) {
		ImageIcon icon=createIcon(a, displaySize); 
		this.setToolTipText(icon.getDescription());

		setIcon(icon);
	}
}
