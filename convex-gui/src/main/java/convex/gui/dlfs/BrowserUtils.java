package convex.gui.dlfs;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.swing.JComponent;
import javax.swing.JOptionPane;

public class BrowserUtils {

	protected static void copyFiles(JComponent parent, List<File> files, Path targetDir) throws IOException {
		fileLoop: for (File f:files) {
			 String fname=f.getName();
			 Path targetPath=targetDir.resolve(fname);
			 while(Files.exists(targetPath)) {
				 fname=JOptionPane.showInputDialog(parent,"File name already exists, enter a new name:", fname);
				 if (fname==null) continue fileLoop;
				 targetPath=targetDir.resolve(fname);
			 }
			 Path p=Files.copy(f.toPath(),targetPath);
			 System.out.println("Copied to: "+p.toString());
			 System.out.println(Files.size(p));
		 }
	}

}
