package convex.gui.manager.windows.state;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;

import javax.swing.tree.TreeNode;

import convex.core.data.Ref;
import convex.core.data.ACell;
import convex.core.data.Cells;
import convex.core.util.Utils;

public class StateTreeNode<T extends ACell> implements TreeNode {

	private final T object;
	private final boolean isContainer;

	public StateTreeNode(T o) {
		this.object = o;
		this.isContainer = Cells.refCount(o)>0;
	}

	private static <R extends ACell> StateTreeNode<R> create(R value) {
		return new StateTreeNode<R>(value);
	}

	@Override
	public TreeNode getChildAt(int childIndex) {
		if (isContainer) {
			ACell child = object.getRef(childIndex).getValue();
			return StateTreeNode.create(child);
		}
		return null;
	}

	@Override
	public int getChildCount() {
		return isContainer ? ((ACell) object).getRefCount() : 0;
	}

	@Override
	public TreeNode getParent() {
		return null;
	}

	@Override
	public int getIndex(TreeNode node) {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public boolean getAllowsChildren() {
		return isContainer;
	}

	@Override
	public boolean isLeaf() {
		return getChildCount() == 0;
	}

	@SuppressWarnings("unchecked")
	@Override
	public Enumeration<? extends TreeNode> children() {

		Ref<ACell>[] childRefs = (isContainer ? ((ACell) object).getChildRefs() : new Ref[0]);
		ArrayList<StateTreeNode<ACell>> tns = new ArrayList<>();
		for (Ref<ACell> r : childRefs) {
			tns.add(StateTreeNode.create(r.getValue()));
		}
		return Collections.enumeration(tns);
	}

	@Override
	public String toString() {
		return Utils.getClassName(object); // +" : "+Utils.toString(object);
	}

}
