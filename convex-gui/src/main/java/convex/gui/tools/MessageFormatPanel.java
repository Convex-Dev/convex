package convex.gui.tools;

import java.awt.BorderLayout;
import java.awt.Color;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;

import convex.core.data.ACell;
import convex.core.data.Blob;
import convex.core.data.Cells;
import convex.core.data.Format;
import convex.core.data.Refs;
import convex.core.exceptions.ParseException;
import convex.core.lang.RT;
import convex.core.lang.Reader;
import convex.core.util.Utils;
import convex.gui.components.ActionPanel;
import convex.gui.components.CodeLabel;
import convex.gui.components.CodePane;
import convex.gui.utils.Toolkit;

@SuppressWarnings("serial")
public class MessageFormatPanel extends JPanel {

	final CodePane dataArea;
	final CodeLabel messageArea;
	private JPanel buttonPanel;
	private JButton clearButton;
	private JPanel instructionsPanel;
	private JLabel lblNewLabel;
	private JTextArea hashLabel;

	public MessageFormatPanel() {
		setLayout(new BorderLayout(0, 0));
		
		instructionsPanel = new JPanel();
		add(instructionsPanel, BorderLayout.NORTH);

		lblNewLabel = new JLabel("Convert data values to encoded binary representations, and vice versa");
		instructionsPanel.add(lblNewLabel);


		JSplitPane splitPane = new JSplitPane();
		splitPane.setOneTouchExpandable(true);
		splitPane.setResizeWeight(0.5);
		splitPane.setOrientation(JSplitPane.VERTICAL_SPLIT);
		add(splitPane, BorderLayout.CENTER);

		// Top panel component

		dataArea = new CodePane();
		dataArea.setToolTipText("Enter data objects here");
		dataArea.setMaxColumns(128);
		dataArea.setFont(Toolkit.MONO_FONT);
		// dataArea.setLineWrap(true);
		dataArea.getDocument().addDocumentListener(Toolkit.createDocumentListener(() -> updateData()));
		splitPane.setLeftComponent(new JScrollPane(dataArea));
		
		// Bottom panel component
		JPanel lowerPanel = new JPanel();
		lowerPanel.setLayout(new BorderLayout(0, 0));

		messageArea = new CodeLabel();
		messageArea.setEditable(true);
		messageArea.setToolTipText("Enter binary hex representation here");
		messageArea.setFont(Toolkit.MONO_FONT);
		messageArea.setBackground(Color.BLACK);
		messageArea.setLineWrap(true);
		messageArea.setMaxColumns(65);
		JScrollPane messageScroll=new JScrollPane(messageArea);
		messageScroll.getViewport().setBackground(Color.BLACK);
		lowerPanel.add(messageScroll, BorderLayout.CENTER);

		splitPane.setRightComponent(lowerPanel);

		hashLabel = new JTextArea();
		hashLabel.setRows(2);
		hashLabel.setToolTipText("Hash code of the data object's serilaised representation = Data Object ID");
		hashLabel.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createEmptyBorder(10,10,10,10), BorderFactory.createRaisedBevelBorder()));
		hashLabel.setBackground(null);
		hashLabel.setFont(Toolkit.MONO_FONT);
		lowerPanel.add(hashLabel, BorderLayout.SOUTH);
		messageArea.getDocument().addDocumentListener(Toolkit.createDocumentListener(() -> updateMessage()));


		buttonPanel = new ActionPanel();
		add(buttonPanel, BorderLayout.SOUTH);

		clearButton = new JButton("Clear",Toolkit.menuIcon(0xe835));
		clearButton.setToolTipText("Press to clear the input areas");
		clearButton.addActionListener(e -> {
			dataArea.setText("");
			messageArea.setText("");
		});
		buttonPanel.add(clearButton);

		updateHashLabel(null,null);
	}

	private void updateMessage() {
		if (!messageArea.isFocusOwner()) return; // prevent mutual recursion
		String data = "";
		String msg = messageArea.getText();
		try {
			Blob b = Blob.parse(Utils.stripWhiteSpace(msg));
			if (b==null) {
				data = "Invalid hex";
			} else {
				ACell o = Format.decodeMultiCell(b);
				data = Utils.print(o);
				updateHashLabel(o,b);
			}
		} catch (ParseException e) {
			data = "Unable to interpret message: " + e.getMessage();
			clearHashLabel();
		} catch (Exception e) {
			data = "Message decoding failed: "+e.getMessage();
			clearHashLabel();
		}
		dataArea.setText(data);
	}

	private void clearHashLabel() {
		updateHashLabel(null,null);
	}

	private void updateData() {
		if (!dataArea.isFocusOwner()) return; // prevent mutual recursion
		String msg = "";
		String data = dataArea.getText();
		clearHashLabel();
		if (!data.isBlank()) try {
			messageArea.setEnabled(false);
			ACell o = Reader.read(data);
			Blob b = Format.encodeMultiCell(o,true);
			updateHashLabel(o,b);
			msg = b.toHexString();
			messageArea.setEnabled(true);
		} catch (Exception e) {
			msg = e.getClass().getSimpleName()+": "+e.getMessage();
		}
		messageArea.setText(msg);
	}
	
	@SuppressWarnings("null")
	private void updateHashLabel(ACell v, Blob b) {
		StringBuilder sb=new StringBuilder();
		boolean empty=(b==null);
		sb.append("Hash:          " + (empty?"<none>":b.getContentHash().toString()));
		sb.append("\n");
		sb.append("Type:          "+(empty?"<none>":RT.getType(v).toString()));
		sb.append("\n");
		sb.append("Encoding Size: "+(empty?"<none>":b.count()));
		sb.append("\n");
		sb.append("Memory Size:   "+(empty?"<none>":Cells.storageSize(v)));
		sb.append("\n");
		sb.append("Cell Count:    "+(empty?"<none>":Refs.totalRefCount(v)));
		sb.append("\n");
		sb.append("Branch Count:  "+(empty?"<none>":Cells.branchCount(v)));
		hashLabel.setText(sb.toString());
	}

}
