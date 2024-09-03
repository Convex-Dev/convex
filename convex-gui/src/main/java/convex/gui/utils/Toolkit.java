package convex.gui.utils;

import java.awt.Color;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.HeadlessException;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.InputMap;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.LookAndFeel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.border.Border;
import javax.swing.border.CompoundBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.DefaultEditorKit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.formdev.flatlaf.FlatDarculaLaf;
import com.formdev.flatlaf.intellijthemes.materialthemeuilite.FlatMaterialOceanicIJTheme;
import com.formdev.flatlaf.util.SystemInfo;

import convex.core.util.Utils;

@SuppressWarnings("serial")
public class Toolkit {
	
	private static Logger log = LoggerFactory.getLogger(Toolkit.class.getName());

	public static final double BASE_SCALE=1.3;
	public static final float SCALE=getUIScale();
	
	public static final int ICON_SIZE = (int) (32*SCALE);
	public static final int IDENTICON_SIZE = (int) (14*SCALE);
	public static final int IDENTICON_SIZE_LARGE = 2*IDENTICON_SIZE;
	public static final int SMALL_ICON_SIZE = (int) (16*SCALE);
	public static final int MAIN_ICON_SIZE = (int) (72*SCALE);

	public static final float DEFAULT_FONT_SIZE=13*SCALE;
	
	public static Font DEFAULT_FONT = new Font(Font.SANS_SERIF,Font.PLAIN,(int)DEFAULT_FONT_SIZE);
	public static Font BIG_FONT = new Font(Font.SANS_SERIF, Font.BOLD, (int)(DEFAULT_FONT_SIZE*1.8));
	public static Font MONO_FONT = new Font(Font.MONOSPACED, Font.PLAIN, (int)(DEFAULT_FONT_SIZE));
	public static Font BIG_MONO_FONT = new Font(Font.MONOSPACED, Font.BOLD, (int)(DEFAULT_FONT_SIZE*1.5));
	public static Font SMALL_MONO_FONT = new Font(Font.MONOSPACED, Font.PLAIN, (int)(DEFAULT_FONT_SIZE*0.8));
	public static Font BUTTON_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, (int)(DEFAULT_FONT_SIZE*1.2));
	
	public static final float SYMBOL_FONT_SIZE= DEFAULT_FONT_SIZE;
	
	public static Font SYMBOL_FONT = new Font(Font.MONOSPACED, Font.PLAIN, (int)SYMBOL_FONT_SIZE);
	
	public static final Color SYMBOL_COLOUR = new Color(100,170,200);
	public static final Color WARNING_COLOUR = Color.ORANGE;
	public static final Color BUTTON_FG = new Color(176,190,197);

	static {
		try {
			if (SystemInfo.isMacOS) {
				// Apple needs some special setup
				setupForApple();
			}
			loadFonts();
			UIManager.getLookAndFeelDefaults().put("defaultFont", DEFAULT_FONT);
			//LookAndFeel laf = installMDLaf();
			LookAndFeel laf=installFlatLaf();
			
			
			if (System.getProperty("os.name").toLowerCase().startsWith("mac")) {
				InputMap im = (InputMap) UIManager.get("TextField.focusInputMap");
				im.put(KeyStroke.getKeyStroke(KeyEvent.VK_C, KeyEvent.META_DOWN_MASK), DefaultEditorKit.copyAction);
				im.put(KeyStroke.getKeyStroke(KeyEvent.VK_V, KeyEvent.META_DOWN_MASK), DefaultEditorKit.pasteAction);
				im.put(KeyStroke.getKeyStroke(KeyEvent.VK_X, KeyEvent.META_DOWN_MASK), DefaultEditorKit.cutAction);
			}
			
			
			UIManager.setLookAndFeel(laf);
			FlatMaterialOceanicIJTheme.setup();
			
			// Override button foreground, too dark by default
			UIManager.put( "Button.foreground", UIManager.get("Label.foreground") );
			// System.out.println(UIManager.get("Label.foreground"));
		} catch (HeadlessException e) {
			// We need this to stop things like tests failing in headless mode (e.g. in CI builds)
			log.warn("Unable to initialise GUI Toolkit due to headless execution mode.");
		} catch (Exception e) {
			e.printStackTrace();
			log.warn("Unable to set look and feel: {}", e);
		}
	}

	protected static void setupForApple() {
		// For Mac, use Convex as the application name
		System.setProperty( "apple.awt.application.name", "Convex" );
		System.setProperty( "apple.awt.application.appearance", "system" );
		System.setProperty( "apple.laf.useScreenMenuBar", "true" );
	}

	protected static LookAndFeel installFlatLaf() {
		FlatDarculaLaf laf=new FlatDarculaLaf();
		return laf;
	}

	private static float getUIScale() {
		try {
			//GraphicsDevice screen = GraphicsEnvironment
			//	    .getLocalGraphicsEnvironment()
			//	    .getDefaultScreenDevice();
			//Double scale=screen.getDefaultConfiguration().getDefaultTransform().getScaleX();
			Double scale=BASE_SCALE;
			System.setProperty( "flatlaf.uiScale", ""+scale );
			log.info("UI Scale: "+scale);
			return (float)(scale.doubleValue());
		} catch (HeadlessException e) {
			return 1.0f;
		}
	}

	// public static final ImageIcon LOCKED_ICON =
	// scaledIcon(36,"/images/ic_lock_outline_black_36dp.png");
	// public static final ImageIcon UNLOCKED_ICON =
	// scaledIcon(36,"/images/ic_lock_open_black_36dp.png");
	//public static final ImageIcon LOCKED_ICON = scaledIcon(ICON_SIZE, "/images/padlock.png");
	//public static final ImageIcon UNLOCKED_ICON = scaledIcon(ICON_SIZE, "/images/padlock-open.png");
	
	public static final ImageIcon LOCKED_ICON = SymbolIcon.get(0xe897,ICON_SIZE,Toolkit.SYMBOL_COLOUR.getRGB());
	public static final ImageIcon UNLOCKED_ICON = SymbolIcon.get(0xe898,ICON_SIZE,Toolkit.WARNING_COLOUR.getRGB());
	public static final ImageIcon WARNING = scaledIcon(ICON_SIZE, "/images/ic_priority_high_black_36dp.png");
	public static final ImageIcon CAKE = scaledIcon(ICON_SIZE, "/images/ic_cake_black_36dp.png");
	public static final ImageIcon CONVEX = scaledIcon(ICON_SIZE, "/images/Convex.png");
	public static final ImageIcon COG = scaledIcon(ICON_SIZE, "/images/cog.png");
	public static final ImageIcon REPL_ICON = scaledIcon(ICON_SIZE, "/images/terminal-icon.png");

	public static final ImageIcon TESTNET_ICON = scaledIcon(MAIN_ICON_SIZE, "/images/testnet.png");
	public static final ImageIcon WWW_ICON = scaledIcon(MAIN_ICON_SIZE, "/images/www.png");
	public static final ImageIcon HACKER_ICON = scaledIcon(MAIN_ICON_SIZE, "/images/hacker.png");
	public static final ImageIcon TERMINAL_ICON = scaledIcon(MAIN_ICON_SIZE, "/images/terminal.png");
	public static final ImageIcon ECOSYSTEM_ICON = scaledIcon(MAIN_ICON_SIZE, "/images/ecosystem.png");
	public static final ImageIcon WALLET_ICON = scaledIcon(MAIN_ICON_SIZE, "/images/wallet.png");
	public static final ImageIcon DLFS_ICON = scaledIcon(MAIN_ICON_SIZE, "/images/filesystem.png");



	public static ImageIcon scaledIcon(int size, String resourcePath) {
		java.net.URL imgURL = Toolkit.class.getResource(resourcePath);
		if (imgURL == null) throw new Error("No image: " + resourcePath);
		ImageIcon imageIcon = new ImageIcon(imgURL);
		Image image = imageIcon.getImage();
		image = image.getScaledInstance(size, size, Image.SCALE_SMOOTH);

		return new ImageIcon(image);
	}

	private static void loadFonts() {
		try {
			{ // Source Code Pro
				InputStream is = Utils.getResourceAsStream("/fonts/SourceCodePro-Regular.ttf");
				MONO_FONT = Font.createFont(Font.TRUETYPE_FONT, is).deriveFont(DEFAULT_FONT_SIZE);
			}
			
			{ // Material Symbols
				InputStream is = Utils.getResourceAsStream("/fonts/MaterialSymbolsSharp.ttf");
				SYMBOL_FONT = Font.createFont(Font.TRUETYPE_FONT, is).deriveFont(SYMBOL_FONT_SIZE);
			}
			DEFAULT_FONT=DEFAULT_FONT.deriveFont(DEFAULT_FONT_SIZE).deriveFont(Font.PLAIN);

		} catch (Exception e) {
			System.err.println("PROBLEM LOADING FONTS:");
			e.printStackTrace();
		}

	}

	/**
	 * Scale an image with interpolation / AA for nicer effects
	 * 
	 * @param src Source image
	 * @param w Width of new image
	 * @param h Height of new image
	 * @return A new, resized image
	 */
	public static BufferedImage smoothResize(BufferedImage src, int w, int h) {
		BufferedImage newImage = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = (Graphics2D) newImage.getGraphics();

		// set up rendering hints
		graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

		graphics.drawImage(src, 0, 0, w, h, null);
		return newImage;
	}
	
	public static BufferedImage pixelResize(BufferedImage src, int w, int h) {
		BufferedImage newImage = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = (Graphics2D) newImage.getGraphics();

		graphics.drawImage(src, 0, 0, w, h, null);
		return newImage;
	}

	public static DocumentListener createDocumentListener(final Runnable a) {
		return new DocumentListener() {
			@Override
			public void insertUpdate(DocumentEvent e) {
				SwingUtilities.invokeLater(a);
			}

			@Override
			public void removeUpdate(DocumentEvent e) {
				SwingUtilities.invokeLater(a);
			}

			@Override
			public void changedUpdate(DocumentEvent e) {
				SwingUtilities.invokeLater(a);
			}
		};
	}

	public static void init() {
		// Empty method, just triggers static initialisation
	}

	public static Image getImage(URL resourceURL) {
		return getDefaultToolkit().getImage(resourceURL);
	}

	public static java.awt.Toolkit getDefaultToolkit() {
		return java.awt.Toolkit.getDefaultToolkit();
	}

	public static void launchBrowser(String url) {
		try {
			if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
				Desktop.getDesktop().browse(new URI(url));
			}
		} catch (IOException | URISyntaxException ex) {
			log.warn("IO Failure launching browser: "+ex);
		}
	}

	public static void showMainFrame(JComponent comp) {
		 JFrame frame = new JFrame("Test Frame"); 
		 frame.getContentPane().add(comp);
		 frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		 frame.pack();
		 frame.setVisible(true);
	}

	/**
	 * Relinquish focus from a component
	 * @param c Component to remove focus from
	 */
	public static void relinquishFocus(Component c) {
		if (c.isFocusable()) {
			c.setFocusable(false);
			c.setFocusable(true);
		}
	}

	public static JMenuItem makeMenu(String name,Runnable op) {
		AbstractAction action=makeAction(name,op);
		JMenuItem mi= new JMenuItem(action);
		return mi;
	}
	
	public static AbstractAction makeAction(String name,Runnable op) {
		return new AbstractAction(name) {
			@Override
			public void actionPerformed(ActionEvent e) {
				op.run();
			}
		};
	}

	/**
	 * Adds a popup menu to a component, including necessary mouse listeners
	 * @param popupMenu Op up menu component
	 */
	public static void addPopupMenu(JComponent comp,javax.swing.JPopupMenu popupMenu) {
		comp.addMouseListener(new MouseAdapter () {
		    public void mousePressed(MouseEvent e) {
		        maybeDisplayPopupMenu(e);
		    }

		    public void mouseReleased(MouseEvent e) {
		        maybeDisplayPopupMenu(e);
		    }

		    private void maybeDisplayPopupMenu(MouseEvent e) {
		    	// This is because different platforms have different mouse events for popup triggers. Ugly.....
		        if (e.isPopupTrigger()) {
		            popupMenu.show(e.getComponent(),e.getX(), e.getY());
		        }
		    }
		});
	}

	public static Border createDialogBorder() {
		return createEmptyBorder(20);
	}

	public static Border createEmptyBorder(int x) {
		return BorderFactory.createEmptyBorder(x, x, x, x);
	}
	
	public static JComponent makeHelp(String helpText) {
		JLabel help=new JLabel(SymbolIcon.get(0xe887,Toolkit.SMALL_ICON_SIZE));
		help.setToolTipText(helpText);
		return help;
	}

	public static JComponent makeNote(String title, String note) {
		// JLabel ta = new JLabel("<html>"+note+"</html>"); // Alternative, but doesn't scale component height?
		JTextArea ta = new JTextArea(note);
		CompoundBorder b=BorderFactory.createCompoundBorder(BorderFactory.createTitledBorder(title), createEmptyBorder(10));
		
		// This allows the text area to shrink, for some odd reason....
		ta.setMinimumSize(new Dimension(50,50));
		
		ta.setBorder(b);
		ta.setEditable(false); // Can't edit notes
		ta.setFocusable(false); // informational only, shouldn't focus
		// ta.setFont(Toolkit.DEFAULT_FONT);
		ta.setLineWrap(true); // for wrapping
		ta.setWrapStyleWord(true); // for nice text when it wraps
		return ta;
	}
	
	public static JComponent makeNote(String note) {
		return makeNote("NOTE",note);
	}

	public static void copyToClipboard(String string) {
		if (string==null) return;
		 Clipboard c = Toolkit.getDefaultToolkit().getSystemClipboard();
		 StringSelection text = new StringSelection(string);
		 c.setContents(text,text);
	}

	public static JComponent withTitledBorder(String title, JComponent comp) {
		Border existingBorder=comp.getBorder();
		Border border=BorderFactory.createTitledBorder(title);
		if (existingBorder!=null) {
			border=BorderFactory.createCompoundBorder(border, existingBorder);
		}
		comp.setBorder(border);
		return comp;
	}

	public static Icon menuIcon(int codePoint) {
		return SymbolIcon.get(codePoint,Toolkit.BUTTON_FONT.getSize());
	}

	public static <E> void scrollToBottom(JScrollPane scrollPane) {
		JScrollBar bar = scrollPane.getVerticalScrollBar();
		bar.setValue(bar.getMaximum());
	}
}
