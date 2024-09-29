package convex.gui.components;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Insets;

import convex.gui.utils.Toolkit;

/**
 * A pane for code editing supporting syntax highlighting etc. Editable by default
 */
@SuppressWarnings("serial")
public class CodePane extends BaseTextPane {
	
	private int maxColumns;

	public CodePane() {
		RightCopyMenu.addTo(this);

		setFont(Toolkit.MONO_FONT);
		// stop catching focus movement keys, useful for Ctrl+up and down etc
		setFocusTraversalKeysEnabled(false);

		setBackground(Color.BLACK);
	}

	@Override public boolean getScrollableTracksViewportWidth() {
		boolean track= super.getScrollableTracksViewportWidth();
		if (maxColumns==0) {
			track=true;
		}
		return track;
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
    public Dimension getMaximumSize() {
		Dimension d=super.getMaximumSize();
		
	    Insets insets = getInsets();
		int columns=getMaxColumns();
		if (columns>0) {
			if ((maxColumns>0)&&(columns>maxColumns)) columns=maxColumns;
			int cw=getColumnWidth();
			d.width = columns * cw + insets.left + insets.right;
		}
		return d;
	}
	
	private int columnWidth;
    protected int getColumnWidth() {
        if (columnWidth == 0) {
            FontMetrics metrics = getFontMetrics(getFont());
            columnWidth = metrics.charWidth('m');
        }
        return columnWidth;
    }

	/**
	 * Gets the length of this document
	 * @return Document length
	 */
	public int docLength() {
		return getDocument().getLength();
	}
}
