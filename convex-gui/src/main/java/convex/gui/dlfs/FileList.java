package convex.gui.dlfs;

import java.awt.Color;
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
import javax.swing.JPopupMenu;

import convex.dlfs.DLPath;
import convex.gui.components.Toast;
import convex.gui.utils.Toolkit;

@SuppressWarnings("serial")
public class FileList extends JList<Path> {

	private Path directory;
	DefaultListModel<Path> model;

	public class FileContextMenu extends JPopupMenu {
		public FileContextMenu() {
		add(Toolkit.makeMenu("Delete",()->{
				try {
					Path p=getSelectedPath();
					System.out.println("Deleting:"+ p);
			    	if (p!=null) Files.delete(p);
			    	refreshList();
				} catch (IOException e) {
					Toast.display(null, "Can't delete file!", Color.ORANGE);
					e.printStackTrace();
				}
			}));
		}
	};
	
	public FileList(Path initialDir,Consumer<Path> obDoubleClick) {
		this.directory=initialDir;
		model=new DefaultListModel<Path>();
		setCellRenderer(new Renderer());
		this.setDragEnabled(true);
		setModel(model);
		this.addMouseListener(new MouseAdapter() {
			@Override
		    public void mouseClicked(MouseEvent e) {
		    	if (e.getClickCount()==2) {
		    		Path p=getSelectedPath();
		    		if ((p!=null)&&Files.isDirectory(p)) {
		    			obDoubleClick.accept(p);
		    		}
		    	}
		    }
		});
		Toolkit.addPopupMenu(this,new FileContextMenu());
	}
	
	public void refreshList() {
		setDirectory(directory);
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
