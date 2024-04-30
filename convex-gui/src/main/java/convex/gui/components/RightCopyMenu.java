package convex.gui.components;

import java.awt.Component;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.text.JTextComponent;

import convex.gui.utils.SymbolIcon;

@SuppressWarnings("serial") 
public class RightCopyMenu extends JPopupMenu implements ActionListener {

	JMenuItem copyMenuItem = new JMenuItem("Copy",SymbolIcon.get(0xe14d));
	JMenuItem cutMenuItem = new JMenuItem("Cut",SymbolIcon.get(0xf08b));
    JMenuItem pasteMenuItem = new JMenuItem("Paste",SymbolIcon.get(0xe14f));
    
	public RightCopyMenu(Component invoker) {
		copyMenuItem.addActionListener(this);
		cutMenuItem.addActionListener(this);
		pasteMenuItem.addActionListener(this);
	        
		add(copyMenuItem);
		add(cutMenuItem);
		add(pasteMenuItem);
		
		setInvoker(invoker);
	}
	
	public static void addTo(JTextComponent tf) {
		tf.addMouseListener(new MouseAdapter() {
			public void mousePressed(MouseEvent e) {
                switch(e.getButton()) {
                    case MouseEvent.BUTTON3: {
                    	Component comp=e.getComponent();
                    	RightCopyMenu menu=new RightCopyMenu(comp);
                        menu.show(comp, e.getX(), e.getY());
                        break;
                    }
                }
            }			
		});
	}

	@Override
	public void actionPerformed(ActionEvent e) {
	     Object source = e.getSource();
	     Component invoker=getInvoker();
	     if (invoker instanceof JTextComponent) {
	    	 JTextComponent tf = (JTextComponent)invoker;
	    	 if (source == cutMenuItem) {
	    		 tf.cut();
	    	 } else if (source == copyMenuItem) {
	    		 tf.copy();
	    	 } else if (source == pasteMenuItem) {
	    		 tf.paste();
	    	 }		
	     }
	}
}
