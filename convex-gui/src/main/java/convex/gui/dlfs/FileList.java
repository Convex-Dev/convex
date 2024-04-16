package convex.gui.dlfs;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.swing.DefaultListModel;
import javax.swing.JList;

import convex.dlfs.DLPath;

@SuppressWarnings("serial")
public class FileList extends JList<Path> {

	private Path directory;
	DefaultListModel<Path> model;

	public FileList(Path initialDir) {
		this.directory=initialDir;
		model=new DefaultListModel<Path>();
		setModel(model);
	}
	
	public Path getDirectory() {
		return directory;
	}

	public void setDirectory(Path newPath) {
		directory=newPath;
		
		model.removeAllElements();
		
		if (!(directory instanceof DLPath)) return;
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
	        for (Path path : stream) {
	        	model.addElement(path.getFileName());
	        }
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
}
