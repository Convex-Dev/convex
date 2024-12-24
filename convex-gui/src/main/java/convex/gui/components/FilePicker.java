package convex.gui.components;

import java.awt.event.ActionEvent;

import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JPanel;
import javax.swing.JTextField;

import net.miginfocom.swing.MigLayout;

@SuppressWarnings("serial")
public class FilePicker extends JPanel {

	private String fileName;
	
	private JTextField fileField;
    private JButton button;
    
    private JFileChooser fileChooser;
    

	public FilePicker(String filename) {
		this.fileName=filename;
		
		this.fileChooser=new JFileChooser();
		
		setLayout(new MigLayout());
		
		fileField=new JTextField(fileName);
		add(fileField);
		
		button=new JButton("...");
		button.addActionListener(this::selectFile);
		add(button);
	}
	
	public void selectFile(ActionEvent e) {
		int selected=fileChooser.showDialog(this, "Select");
		if (selected==JFileChooser.APPROVE_OPTION) {
			fileField.setText(fileChooser.getSelectedFile().getAbsolutePath());
		}
	}
}
