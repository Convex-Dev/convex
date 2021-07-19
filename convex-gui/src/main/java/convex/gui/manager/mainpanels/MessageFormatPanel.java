package convex.gui.manager.mainpanels;

import java.awt.BorderLayout;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;

import convex.core.data.ACell;
import convex.core.data.Blob;
import convex.core.data.Format;
import convex.core.exceptions.ParseException;
import convex.core.lang.Reader;
import convex.core.util.Utils;
import convex.gui.components.ActionPanel;
import convex.gui.manager.PeerGUI;
import convex.gui.utils.Toolkit;

@SuppressWarnings("serial")
public class MessageFormatPanel extends JPanel {

	final JTextArea dataArea;
	final JTextArea messageArea;
	private JPanel buttonPanel;
	protected PeerGUI manager;
	private JButton clearButton;
	private JPanel upperPanel;
	private JPanel instructionsPanel;
	private JLabel lblNewLabel;
	private JTextField hashLabel;

	private static String HASHLABEL = "Hash: ";

	public MessageFormatPanel(PeerGUI manager) {
		this.manager = manager;
		setLayout(new BorderLayout(0, 0));

		JSplitPane splitPane = new JSplitPane();
		splitPane.setOneTouchExpandable(true);
		splitPane.setResizeWeight(0.5);
		splitPane.setOrientation(JSplitPane.VERTICAL_SPLIT);
		add(splitPane, BorderLayout.CENTER);

		// Top panel component
		upperPanel = new JPanel();
		upperPanel.setLayout(new BorderLayout(0, 0));
		dataArea = new JTextArea();
		dataArea.setToolTipText("Enter data objects here");
		upperPanel.add(dataArea, BorderLayout.CENTER);
		dataArea.setFont(Toolkit.MONO_FONT);
		dataArea.setLineWrap(true);
		dataArea.getDocument().addDocumentListener(Toolkit.createDocumentListener(() -> updateData()));

		// Bottom panel component
		JPanel lowerPanel = new JPanel();
		lowerPanel.setLayout(new BorderLayout(0, 0));

		messageArea = new JTextArea();
		messageArea.setToolTipText("Enter binary hex representation here");
		messageArea.setFont(Toolkit.MONO_FONT);
		lowerPanel.add(messageArea, BorderLayout.CENTER);

		splitPane.setRightComponent(lowerPanel);

		hashLabel = new JTextField(HASHLABEL);
		hashLabel.setToolTipText("Hash code of the data object's serilaised representation = Data Object ID");
		hashLabel.setBorder(null);
		hashLabel.setBackground(null);
		hashLabel.setFont(Toolkit.MONO_FONT);
		lowerPanel.add(hashLabel, BorderLayout.SOUTH);
		messageArea.getDocument().addDocumentListener(Toolkit.createDocumentListener(() -> updateMessage()));

		splitPane.setLeftComponent(upperPanel);

		buttonPanel = new ActionPanel();
		add(buttonPanel, BorderLayout.SOUTH);

		clearButton = new JButton("Clear");
		clearButton.setToolTipText("Press to clear the input areas");
		buttonPanel.add(clearButton);

		instructionsPanel = new JPanel();
		add(instructionsPanel, BorderLayout.NORTH);

		lblNewLabel = new JLabel("Use this fine tool to convert data to formatted binary messages, and vice versa");
		instructionsPanel.add(lblNewLabel);
		clearButton.addActionListener(e -> {
			dataArea.setText("");
			messageArea.setText("");
		});
	}

	private void updateMessage() {
		if (!messageArea.isFocusOwner()) return; // prevent mutual recursion
		String data = "";
		String msg = messageArea.getText();
		try {
			Blob b = Blob.fromHex(Utils.stripWhiteSpace(msg));
			Object o = Format.read(b);
			data = Utils.print(o);
			hashLabel.setText(HASHLABEL + b.getContentHash().toHexString());
		} catch (ParseException e) {
			data = "Unable to interpret message: " + e.getMessage();
			hashLabel.setText(HASHLABEL + " <invalid>");
		} catch (Exception e) {
			data = e.getMessage();
		}
		dataArea.setText(data);
	}

	private void updateData() {
		if (!dataArea.isFocusOwner()) return; // prevent mutual recursion
		String msg = "";
		String data = dataArea.getText();
		hashLabel.setText(HASHLABEL + " <invalid>");
		if (!data.isBlank()) try {
			messageArea.setEnabled(false);
			ACell o = Reader.read(data);
			Blob b = Format.encodedBlob(o);
			hashLabel.setText(HASHLABEL + b.getContentHash().toHexString());
			msg = b.toHexString();
			messageArea.setEnabled(true);
		} catch (ParseException e) {
			msg = e.getMessage();
		} catch (Exception e) {
			msg = e.getMessage();
		}
		messageArea.setText(msg);
	}

}
