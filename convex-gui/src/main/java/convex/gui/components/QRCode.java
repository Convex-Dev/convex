package convex.gui.components;

import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.image.BufferedImage;

import javax.swing.JPanel;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;

import convex.core.util.Utils;
import net.miginfocom.swing.MigLayout;

@SuppressWarnings("serial")
public class QRCode extends JPanel {

	protected final String data;
	protected  BufferedImage image;

	public QRCode(String data, int scale) {
		this.data=data;
	
		image=createQR(data,scale);
		
		this.setLayout(new MigLayout());
		this.setMinimumSize(new Dimension(image.getWidth(),image.getHeight()));
		this.setToolTipText(data);
	}

	public static  BufferedImage createQR(String data,int scale) {
		try {
			BitMatrix matrix=new MultiFormatWriter().encode(data, BarcodeFormat.QR_CODE, scale, scale);
			return MatrixToImageWriter.toBufferedImage(matrix);
		} catch (WriterException e) {
			throw Utils.sneakyThrow(e);
		
		}  
	}
	
	@Override
    protected void paintComponent(Graphics g)
    {
        super.paintComponent(g);
        g.drawImage(image, 0, 0, this);
    }
}
