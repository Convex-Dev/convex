package convex.gui.dlfs;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JList;

import convex.dlfs.DLPath;

@SuppressWarnings("serial")
public class FileList extends JList<Path> {

	public class Renderer extends DefaultListCellRenderer {
		  @Override  public Renderer getListCellRendererComponent(
			        JList<?> list,
			        Object value,
			        int index,
			        boolean isSelected,
			        boolean cellHasFocus) {
			  super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
			  
			  if (value instanceof Path) {
				  Path p=(Path)value;
				  setIcon(BrowserUtils.getFileIcon(p));
				  setText(p.getFileName().toString());
			  }
			  return this;
		  }
	}

	private Path directory;
	DefaultListModel<Path> model;

	public FileList(Path initialDir) {
		this.directory=initialDir;
		model=new DefaultListModel<Path>();
		setCellRenderer(new Renderer());
		this.setDragEnabled(true);
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
	        	model.addElement(path);
	        }
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
}
