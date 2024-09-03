package convex.gui.dlfs;

import java.nio.file.Path;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;

import convex.core.util.ThreadUtils;
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
	
	DirectoryTree directoryTree;
	FileList fileList;
	
	CodeLabel infoLabel=new CodeLabel("READY");

	private DLPath selectedPath;

	private PreviewPanel previewPanel;

	
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
		//directoryTree.setPreferredSize(new Dimension(250,500));
		
		fileList=new FileList(selectedPath,p->setSelectedPath(p));
		fileList.setTransferHandler(new DnDTransferHandler(this) {
			@Override
			protected Path getTargetPath() {
				return getSelectedPath();
			}
		});
		fileList.addListSelectionListener(e->{
			Path p=fileList.getSelectedPath();
			previewPanel.setPath(p);
		});
		//fileList.setPreferredSize(new Dimension(250,500));

		JScrollPane listScrollPane=new JScrollPane(fileList);
		
		JSplitPane filesSplitPane=new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,new JScrollPane(directoryTree), listScrollPane);
		filesSplitPane.setResizeWeight(0.5);
		
		previewPanel=new PreviewPanel();
		JScrollPane scrollPreview=new JScrollPane(previewPanel);
		
		JSplitPane splitPane=new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,filesSplitPane,scrollPreview);
		splitPane.setResizeWeight(0.5);
		add(splitPane,"dock center");
		
		pathLabel=new JLabel("/");
		add(pathLabel,"dock north");

		add(infoLabel,"dock south");
		
		directoryTree.setSelectionPath(directoryTree.getPathForRow(0));
		
		ThreadUtils.runVirtual(()->{
			try {
				while (fileSystem.isOpen()) {
					fileSystem.updateTimestamp();
					Thread.sleep(100);
				}
			} catch (InterruptedException e) {
				// set interrupt flag	
				Thread.currentThread().interrupt();  
			}
		});
	}
	
	public DLFileSystem getFileSystem() {
		return fileSystem;
	}
	
	void setSelectedPath(Path newPath) {
		if (!(newPath instanceof DLPath)) {
			pathLabel.setText("No path selected");
			selectedPath=null;
			return;
		}		
		selectedPath=(DLPath) newPath;
		fileList.setDirectory(newPath);
		// Some way to update directory tree selection?
		
		infoLabel.setText("ROOT HASH: " +fileSystem.getRootHash()+"\n"+
				"NODE HASH: " +fileSystem.getNodeHash(selectedPath)+"\n");
		

		pathLabel.setText(newPath.toUri().toString());
		
		previewPanel.setPath(newPath);
	}

	public DLPath getSelectedPath() {
		return selectedPath;
	}

	public void refreshView() {
		setSelectedPath(selectedPath);
	}

}
