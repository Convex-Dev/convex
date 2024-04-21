package convex.gui.dlfs;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JOptionPane;
import javax.swing.UIManager;
import javax.swing.filechooser.FileSystemView;

public class BrowserUtils {

	protected static boolean copyFiles(JComponent parent, List<File> files, Path targetDir) throws IOException {
		boolean copied=false;
		fileLoop: for (File f:files) {
			 String fname=f.getName();
			 Path targetPath=targetDir.resolve(fname);
			 while(Files.exists(targetPath)) {
				 fname=JOptionPane.showInputDialog(parent,"File name already exists, enter a new name:", fname);
				 if (fname==null) continue fileLoop;
				 targetPath=targetDir.resolve(fname);
			 }
			 Path p=Files.copy(f.toPath(),targetPath);
			 System.out.println("Copied "+Files.size(p)+" bytes to: "+p.toString());
			 copied=true;
			 
		 }
		return copied;
	}

	@SuppressWarnings("null")
	protected static Icon getFileIcon(Path path) {
		String iconName= null;
		Icon icon =null;
		if (Files.isDirectory(path)) {
			iconName="FileView.directoryIcon";
		} else if (Files.isRegularFile(path)) {
			iconName= "FileView.fileIcon";
		} else {
			iconName= "FileView.hardDriveIcon";
		}
		
		if (iconName!=null) {
			if (path.getNameCount()==0) {
				icon = UIManager.getIcon("FileView.hardDriveIcon"); // root icon
			} else try {
				icon= FileSystemView.getFileSystemView().getSystemIcon( path.toFile() );
			} catch (Exception e) {
				// ignore
			}
		}
		if (icon==null) icon = UIManager.getIcon(iconName);
		return icon;
	}

}
