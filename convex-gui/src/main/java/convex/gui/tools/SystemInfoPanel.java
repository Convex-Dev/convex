package convex.gui.tools;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.swing.JLabel;
import javax.swing.JPanel;

import convex.core.crypto.Hashing;
import convex.core.text.Text;
import convex.core.util.FileUtils;
import convex.core.util.Utils;
import convex.gui.components.ActionButton;
import convex.gui.utils.Toolkit;
import net.miginfocom.swing.MigLayout;

@SuppressWarnings("serial")
public class SystemInfoPanel extends JPanel {
	public SystemInfoPanel() {
		MigLayout layout = new MigLayout("wrap 1","[]");
		this.setLayout(layout);
		
		JPanel versionPanel=new JPanel();
		versionPanel.setLayout(new MigLayout("wrap 2"));
		versionPanel.add(new JLabel("Code Version:"));
		versionPanel.add(new JLabel(Utils.getVersion()));
		try {
			versionPanel.add(new JLabel("Running code at:"));
			URL sourceLocation=SystemInfoPanel.class.getProtectionDomain().getCodeSource().getLocation();
			String path = sourceLocation.getPath();
			String decodedPath = URLDecoder.decode(path, "UTF-8");
			versionPanel.add(new JLabel(decodedPath));
			
			Path jarFile=new File(sourceLocation.toURI()).toPath();
			versionPanel.add(new JLabel("SHA256 Hash: "));
			if (Files.isRegularFile(jarFile)) {
				JPanel hashPanel=new JPanel();
				hashPanel.add(new ActionButton("Compute...,",0xe8b6, e->{
					try {
						String hash;
						hash = Hashing.sha256(FileUtils.loadFileAsBytes(jarFile)).toHexString();
						hashPanel.removeAll();
						hashPanel.add(new JLabel(hash.toUpperCase()));
					} catch (IOException e1) {
						// TODO Auto-generated catch block
						e1.printStackTrace();
					}
				})); 
				
				versionPanel.add(hashPanel);
			} else {
				versionPanel.add(new JLabel("Not a file:  "+jarFile));
			}
		} catch (Exception e) {
			// ignore
		}
		
		add(Toolkit.withTitledBorder("Convex Version", versionPanel));
		
		JPanel systemPanel=new JPanel();
		systemPanel.setLayout(new MigLayout("wrap 2"));
		systemPanel.add(new JLabel("Operating System:"));
		systemPanel.add(new JLabel(System.getProperty("os.name")));
		systemPanel.add(new JLabel("Available Processors:"));
		systemPanel.add(new JLabel(Integer.toString(Runtime.getRuntime().availableProcessors())));
		add(Toolkit.withTitledBorder("System Information", systemPanel));

		JPanel javaPanel=new JPanel();
		javaPanel.add(new JLabel("Java Version:"));
		javaPanel.add(new JLabel(Runtime.version().toString()));
		javaPanel.add(new JLabel("Maximum Memory:"));
		javaPanel.add(new JLabel(Text.toFriendlyNumber(Runtime.getRuntime().maxMemory())));
		add(Toolkit.withTitledBorder("Java Runtime", javaPanel));

	}

}
