package convex.gui.dlfs;

import java.nio.file.Path;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTree;

import convex.dlfs.DLFileSystem;
import convex.dlfs.DLPath;
import convex.gui.components.CodeLabel;
import net.miginfocom.swing.MigLayout;

/**
 * A file browser panel for DLFS directories
 */
@SuppressWarnings("serial")
public class DLFSPanel extends JPanel {

	protected DLFileSystem fileSystem;
	
	JLabel pathLabel;
	
	JTree directoryTree;
	FileList fileList;
	
	CodeLabel infoLabel=new CodeLabel("READY");

	private DLPath selectedPath;

	
	public DLFSPanel(DLFileSystem dlfs) {
		this.fileSystem=dlfs;
		selectedPath=dlfs.getRoot();
		setLayout(new MigLayout());
		
		directoryTree = new DirectoryTree(dlfs);
		// treeView.setBackground(Color.BLACK);
		directoryTree.setTransferHandler(new DnDTransferHandler(this) {
			@Override
			protected Path getTargetPath() {
				return getSelectedPath();
			}
		});
		
		directoryTree.addTreeSelectionListener(e->{
			DirectoryTree.Node node=(DirectoryTree.Node)directoryTree.getLastSelectedPathComponent();
			if (node==null) {
				setSelectedPath(null);
			} else {
				Path p=node.getFilePath();
				setSelectedPath(p);
			}
		});
		
		fileList=new FileList(selectedPath);
		fileList.setTransferHandler(new DnDTransferHandler(this) {
			@Override
			protected Path getTargetPath() {
				return getSelectedPath();
			}
		});
		JScrollPane listScrollPane=new JScrollPane(fileList);
		
		JSplitPane splitPane=new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,new JScrollPane(directoryTree), listScrollPane);
		add(splitPane,"dock center");
		
		pathLabel=new JLabel("/");
		add(pathLabel,"dock north");

		add(infoLabel,"dock south");
		
		directoryTree.setSelectionPath(directoryTree.getPathForRow(0));
	}
	
	void setSelectedPath(Path newPath) {
		if (!(newPath instanceof DLPath)) {
			pathLabel.setText("No path selected");
			selectedPath=null;
			return;
		}		
		selectedPath=(DLPath) newPath;
		fileList.setDirectory(newPath);
		
		infoLabel.setText("ROOT HASH: " +fileSystem.getRootHash()+"\n"+
				"NODE HASH: " +fileSystem.getNodeHash(selectedPath)+"\n");
		

		pathLabel.setText(newPath.toUri().toString());
	}

	public DLPath getSelectedPath() {
		return selectedPath;
	}

}
