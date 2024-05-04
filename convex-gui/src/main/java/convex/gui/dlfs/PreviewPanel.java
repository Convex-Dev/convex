package convex.gui.dlfs;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import javax.swing.BorderFactory;
import javax.swing.JPanel;

import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.util.Utils;
import convex.dlfs.DLFSNode;
import convex.dlfs.DLPath;
import convex.gui.components.CodePane;
import net.miginfocom.swing.MigLayout;

@SuppressWarnings("serial")
public class PreviewPanel extends JPanel {

	protected Path path;
	private CodePane information;

	public PreviewPanel() {
		setLayout(new MigLayout("wrap 1"));
		information=new CodePane();
		information.setBackground(null);
		information.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
		//
		// header.setPreferredSize(new Dimension(400,50));
		add(information,"span");
		
		setPath(null);
	}
	
	DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm z")
            .withZone(ZoneId.systemDefault());
	
	public void setPath(Path path) {
		this.path=path;
		StringBuilder sb=new StringBuilder();
		try {
			if ((path!=null)&&Files.exists(path)) {
				
				boolean isDir=Files.isDirectory(path);
				Path fname=(path.getFileName());
				
				sb.append("Name:        "+((fname==null)?"<root>":fname.toString())+"\n");
				sb.append("Path:        "+path.toString()+"\n");
				sb.append("Type:        "+(isDir?"Directory":"File")+"\n");
				sb.append("\n");
				
				Instant utime=Files.getLastModifiedTime(path).toInstant();
				sb.append("Modified:    "+formatter.format(utime)+"\n");
				sb.append("\n");
				sb.append("             "+utime+"\n");
				sb.append("\n");
				
				if (path instanceof DLPath) {
					DLPath dp=(DLPath)path;
					AVector<ACell> node=dp.getFileSystem().getNode(dp);
					if (isDir) {
						try (DirectoryStream<Path> ds=Files.newDirectoryStream(path)) {
							sb.append("DLFS Directory accessible\n");
						}
						sb.append("Entries:     "+DLFSNode.getDirectoryEntries(node).count()+"\n");
					} else {
						ABlob data=DLFSNode.getData(node);
						sb.append("File Size:   "+data.count()+"\n");
						sb.append("Data Hash:   "+data.getHash()+"\n");
					}
					sb.append("Node Hash:   "+node.getHash()+"\n");
				} else {
					sb.append("Not a DLFS file: "+Utils.getClassName(path));
				}
			} else {
				sb.append("No file selected");
			}
		} catch (Exception e) {
			e.printStackTrace();
			sb.append("\n\nERROR: "+e.getMessage());
		}
		information.setText(sb.toString());
		
	}
}
