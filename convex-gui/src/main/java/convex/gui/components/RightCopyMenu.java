package convex.gui.components;

import java.awt.Component;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.text.JTextComponent;

import convex.gui.utils.Toolkit;

@SuppressWarnings("serial") 
public class RightCopyMenu extends JPopupMenu implements ActionListener {

	JMenuItem copyMenuItem = new JMenuItem("Copy",Toolkit.menuIcon(0xe14d));
	JMenuItem cutMenuItem = new JMenuItem("Cut",Toolkit.menuIcon(0xf08bE));
    JMenuItem pasteMenuItem = new JMenuItem("Paste",Toolkit.menuIcon(0xe14f));
    
    JTextComponent comp;
    
	public RightCopyMenu(JTextComponent invoker) {
		this.comp=invoker;
		copyMenuItem.addActionListener(this);
		cutMenuItem.addActionListener(this);
		pasteMenuItem.addActionListener(this);
	        
		add(copyMenuItem);
		if (invoker.isEditable()) {	
			add(cutMenuItem);
			add(pasteMenuItem);
		}
		
		setInvoker(invoker);
	}
	
	public static void addTo(JTextComponent tf) {
		RightCopyMenu menu=new RightCopyMenu(tf);
		
		tf.addMouseListener(new MouseAdapter() {
			public void mousePressed(MouseEvent e) {
                switch(e.getButton()) {
                    case MouseEvent.BUTTON3: {                    	
                        menu.show(tf, e.getX(), e.getY());
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
