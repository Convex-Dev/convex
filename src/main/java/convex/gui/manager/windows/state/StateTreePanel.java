package convex.gui.manager.windows.state;

import java.awt.BorderLayout;
import java.awt.Dimension;

import javax.swing.JPanel;
import javax.swing.JTree;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.ExpandVetoException;
import javax.swing.tree.TreeModel;
import javax.swing.tree.TreePath;

import convex.core.data.ACell;
import convex.core.data.ARecord;
import convex.core.data.Format;
import convex.core.data.IRefContainer;
import convex.core.data.Keyword;
import convex.core.data.ListMap;
import convex.core.data.MapEntry;
import convex.core.util.Utils;

@SuppressWarnings("serial")
public class StateTreePanel extends JPanel {

	private final Object state;

	private static class Node extends DefaultMutableTreeNode {
		private final boolean container;
		private boolean loaded = false;
		private final String name;

		public Node(String name, Object val) {
			super(val);
			this.name = name;
			container = (val instanceof IRefContainer);
		}

		public Node(Object val) {
			this(null, val);
		}

		@Override
		public boolean isLeaf() {
			return !container;
		}

		private static String getString(Object val) {
			if (Format.isEmbedded(val)) {
				return val.toString();
			}

			if (val instanceof ACell) {
				ACell r = (ACell) val;
				return r.getClass().getSimpleName() + " [" + r.getHash().toHexString(6) + "...]";
			}

			return Utils.getClassName(val);
		}

		@Override
		public String toString() {
			if (name != null) return name;
			return getString(this.userObject);
		}

		@SuppressWarnings("rawtypes")
		public void loadChildren() {
			if (loaded) return;
			loaded = true;

			if (userObject instanceof ARecord) {
				ARecord r = (ARecord) userObject;
				for (Keyword k : r.getKeys()) {
					Object c = r.get(k);
					add(new Node(k + " = " + getString(c), c));
				}
				return;
			} else if (userObject instanceof ListMap) {
				ListMap m = (ListMap) userObject;
				for (Object oe : m.entrySet()) {
					MapEntry e = (MapEntry) oe;
					Object c = e.getValue();
					add(new Node(getString(e.getKey()) + " = " + getString(c), c));
				}
				return;
			}

			if (!container) return;
			IRefContainer rc = (IRefContainer) userObject;
			int n = rc.getRefCount();
			for (int i = 0; i < n; i++) {
				Object child = rc.getRef(i).getValue();
				add(new Node(child));
			}

		}

	}

	private static final TreeWillExpandListener expandListener = new TreeWillExpandListener() {
		@Override
		public void treeWillExpand(TreeExpansionEvent tee) throws ExpandVetoException {
			TreePath path = tee.getPath();
			Node tn = (Node) path.getLastPathComponent();
			tn.loadChildren();
		}

		@Override
		public void treeWillCollapse(TreeExpansionEvent tee) throws ExpandVetoException {
			/* Nothing to do */
		}
	};

	public StateTreePanel(Object state) {
		this.state = state;
		setLayout(new BorderLayout());
		setPreferredSize(new Dimension(600, 400));

		// StateTreeNode<State> root=new StateTreeNode<>(this.state);

		Node tNode = new Node(this.state);

		tNode.setAllowsChildren(true);
		tNode.loadChildren();
		TreeModel tModel = new DefaultTreeModel(tNode);
		JTree tree = new JTree(tModel);
		tree.addTreeWillExpandListener(expandListener);
		tree.expandPath(new TreePath(tNode.getPath()));

		add(tree, BorderLayout.CENTER);
	}
}
