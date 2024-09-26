package convex.gui.state;

import java.awt.BorderLayout;
import java.awt.Dimension;

import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.ExpandVetoException;
import javax.swing.tree.TreeModel;
import javax.swing.tree.TreePath;

import convex.core.data.ACell;
import convex.core.data.ACountable;
import convex.core.data.ADataStructure;
import convex.core.data.AMap;
import convex.core.data.ARecord;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Address;
import convex.core.data.Keyword;
import convex.core.data.MapEntry;
import convex.core.lang.RT;
import convex.core.util.Utils;

@SuppressWarnings("serial")
public class StateTreePanel extends JPanel {

	private final ACell state;

	private static class Node extends DefaultMutableTreeNode {
		private boolean container;
		private boolean loaded = false;
		private String name;

		public Node(String name, ACell val) {
			super(val);
			if (val instanceof ADataStructure) {
				if (RT.count(val)>0) {
					container=true;
				} else {
					name = name+ " (empty)";
				}
			}
			this.name = name;
		}

		public Node(ACell val) {
			this(null, val);
		}

		@Override
		public boolean isLeaf() {
			return !container;
		}

		private static String getString(ACell a) {
			if (a instanceof AString) {
				return "\""+a.toString()+"\"";
			} else if (a instanceof Address) {
				return a.toString();
			} else if (a instanceof ACountable) {
				return Utils.getClass(a).getSimpleName();
			} else {
				AString s=RT.print(a);
				return (s==null)?"<too big>":s.toString();
			}
		}

		@Override
		public String toString() {
			if (name != null) return name;
			name = getString((ACell)this.userObject);
			return name;
		}

		@SuppressWarnings("rawtypes")
		public void loadChildren() {
			if (loaded) return;
			loaded = true;
			ACell a=(ACell)userObject;

			if (a instanceof ARecord) {
				ARecord r = (ARecord) a;
				for (Keyword k : r.getKeys()) {
					ACell c = r.get(k);
					add(new Node(k + " = " + getString(c), c));
				}
				return;
			} else if (a instanceof AMap) {
				AMap m = (AMap) a;
				long n=m.count();
				for (long i=0; i<n; i++) {
					MapEntry e = m.entryAt(i);
					ACell c = e.getValue();
					add(new Node(RT.toString(e.getKey()) + " -> " + getString(c), c));
				}
				return;
			} else if (a instanceof AVector) {
				AVector v = (AVector) a;
				long n=v.count();
				for (long i=0; i<n; i++) {
					ACell c = v.get(i);
					add(new Node("["+i + "] -> " + getString(c), c));
				}
				return;
			} else {
				if (!container) return;
				ACell rc = (ACell) a;
				int n = rc.getRefCount();
				for (int i = 0; i < n; i++) {
					ACell child = rc.getRef(i).getValue();
					add(new Node(child));
				}
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

	public StateTreePanel(ACell state) {
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

		add(new JScrollPane(tree), BorderLayout.CENTER);
	}
}
