package convex.gui.utils;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.WeakHashMap;

import javax.swing.ImageIcon;
import javax.swing.JLabel;

@SuppressWarnings("serial")
public class SymbolIcon extends ImageIcon {

	private static final Color SYMBOL_COLOUR = new Color(150,200,255);
	
	
	private static WeakHashMap<Long,SymbolIcon> cache= new WeakHashMap<>();
	
	public SymbolIcon(BufferedImage image) {
		super(image);
	}
	
	private static SymbolIcon create(int codePoint, int size) {
		 BufferedImage image =new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		 
		 char[] c=Character.toChars(codePoint);
		 String s=new String(c);
		 
		 
		 JLabel label = new JLabel(s);
		 label.setForeground(SYMBOL_COLOUR);
		 
		 // set font size so we get the correct pixel size
		 float fontSize= 72.0f * size / Toolkit.SCREEN_RES;
	     label.setFont(Toolkit.SYMBOL_FONT.deriveFont(fontSize));
	     
	     label.setHorizontalTextPosition(JLabel.CENTER);
		 label.setVerticalTextPosition(JLabel.CENTER);
		
		 label.setSize(size, size);
	     
		 Graphics2D g = image.createGraphics();
		 // have antialiasing on for better visuals
		 g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		 label.print(g);
		 
		 return new SymbolIcon(image);
	}
	
	public static SymbolIcon get(int codePoint) {
		return get(codePoint,Toolkit.SYMBOL_SIZE);
	}

	public static SymbolIcon get(int codePoint, int size) {
		long id=codePoint+size*0x100000000L;
		SymbolIcon result=cache.get(id);
		
		if (result!=null) return result;
		
		result = create(codePoint,size);
		cache.put(id,result);
		
		return result;
	}
}
