package convex.gui.utils;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.WeakHashMap;

import javax.swing.ImageIcon;
import javax.swing.JLabel;

@SuppressWarnings("serial")
public class SymbolIcon extends ImageIcon {

	private static WeakHashMap<Long,SymbolIcon> cache= new WeakHashMap<>();
	
	public SymbolIcon(BufferedImage image) {
		super(image);
	}
	
	private static SymbolIcon create(int codePoint, double dsize, int colour) {
		 int size=(int)dsize;
		 BufferedImage image =new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		 
		 char[] c=Character.toChars(codePoint);
		 String s=new String(c);
		 
		 
		 JLabel label = new JLabel(s);
		 label.setForeground(new Color(colour&0xffffff));
		 
		 // set font size so we get the correct pixel size
	     Font font=Toolkit.SYMBOL_FONT.deriveFont((float)(size));
		 label.setFont(font);
	     
	     label.setHorizontalAlignment(JLabel.CENTER);
		 label.setVerticalAlignment(JLabel.CENTER);
		
		 label.setSize(size, size);
	     
		 Graphics2D g = image.createGraphics();
		 // have antialiasing on for better visuals
		 g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		 label.print(g);
		 
		 return new SymbolIcon(image);
	}
	
	public static SymbolIcon get(int codePoint) {
		return get(codePoint,Toolkit.SMALL_ICON_SIZE,Toolkit.SYMBOL_COLOUR.getRGB());
	}
	
	public static SymbolIcon get(int codePoint, double size) {
		return get(codePoint,size,Toolkit.SYMBOL_COLOUR.getRGB());
	}


	public static SymbolIcon get(int codePoint, double size, int colour) {
		int sz=(int)size;
		long id=codePoint+(sz*0x100000000L)+(colour*100000000000L);
		SymbolIcon result=cache.get(id);
		
		if (result!=null) return result;
		
		result = create(codePoint,size,colour);
		cache.put(id,result);
		
		return result;
	}
}
