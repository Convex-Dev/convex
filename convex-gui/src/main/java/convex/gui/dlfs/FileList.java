package convex.gui.dlfs;

import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JList;

import convex.dlfs.DLPath;

@SuppressWarnings("serial")
public class FileList extends JList<Path> {

	private Path directory;
	DefaultListModel<Path> model;

	public FileList(Path initialDir,Consumer<Path> onSelect) {
		this.directory=initialDir;
		model=new DefaultListModel<Path>();
		setCellRenderer(new Renderer());
		this.setDragEnabled(true);
		setModel(model);
		this.addMouseListener(new MouseAdapter() {
		    public void mouseClicked(MouseEvent e) {
		    	if (e.getClickCount()==2) {
		    		Path p=getSelectedPath();
		    		if ((p!=null)&&Files.isDirectory(p)) {
		    			onSelect.accept(p);
		    		}
		    	}
		    }
		});
	}
	
	protected Path getSelectedPath() {
		Object o=this.getSelectedValue();
		if (o instanceof Path) return (Path)o;
		return null;
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
	        	if (Files.exists(path)) {
	        		// might not exist if a tombstone....
	        		model.addElement(path);
	        	}
	        }
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
	
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

}
