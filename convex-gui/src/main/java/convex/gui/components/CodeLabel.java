package convex.gui.components;

import java.awt.Dimension;
import java.awt.Insets;

import javax.swing.JTextArea;

import convex.core.text.Text;
import convex.gui.utils.Toolkit;

/**
 * A simple label for multi-line text / code components
 * 
 * Set maxColumns to constrain size
 */
@SuppressWarnings("serial")
public class CodeLabel extends JTextArea {

	private static final int EXTRA = 5;

	public CodeLabel() {
		this("");
	}
	
	private int maxColumns=0;
	
	public CodeLabel(String text) {
		super(Text.lineCount(text),0);
		this.setText(text);
		this.setEditable(false);
		this.setFont(Toolkit.MONO_FONT);
		
		RightCopyMenu.addTo(this);
	}

	/**
	 * @return Maximum number of columns when resizing
	 */
	public int getMaxColumns() {
		return maxColumns;
	}

	/**
	 * @param maxColumns Number of column maximum
	 */
	public void setMaxColumns(int maxColumns) {
		if (maxColumns<0) throw new IllegalArgumentException("Max columns must be 0 or positive");
		this.maxColumns = maxColumns;
	}
	
	@Override
    public Dimension getPreferredSize() {
        Dimension d = new Dimension(400,400);
        Insets insets = getInsets();

        int columns=getColumns();
        if (columns==0) columns = maxColumns;
        if (columns != 0) {
        	// constrain preferred columns if maxColumns is set
        	if ((maxColumns>0)&&(columns>maxColumns)) columns=maxColumns;
        	
            d.width = columns * getColumnWidth() +
                    insets.left + insets.right+EXTRA;
        }
        
        int rows=getRows();
        if (rows != 0) {
            d.height = rows * getRowHeight() +
                                insets.top + insets.bottom;
        }
        return d;
    }
	
	public Dimension getPreferredScrollableViewportSize() {
        return getPreferredSize();
	}
	
	@Override
    public boolean getScrollableTracksViewportWidth() {
		if (maxColumns!=0) return false;
        return super.getScrollableTracksViewportWidth();
    }

	@Override
    public Dimension getMaximumSize() {
		int cw=getColumnWidth();
		Dimension d=new Dimension(cw,cw);
	    Insets insets = getInsets();
		int columns=getColumns();
		if ((maxColumns>0)&&(columns>maxColumns)) columns=maxColumns;
		d.width = columns * cw + insets.left + insets.right+EXTRA;
		
		return d;
	}
 
    
    @Override
    public void setColumns(int columns) {
    	if ((maxColumns!=0)&&(columns>maxColumns)) columns=maxColumns;
    	super.setColumns(columns);
    }
	

}
