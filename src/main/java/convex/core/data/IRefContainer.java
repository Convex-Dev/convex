package convex.core.data;

/**
 * Interface for data objects that may contain refs to other data
 * 
 * Rules: 1. An Object that does not contain any Refs must be persisted as a
 * single cell. 2. If an object contains Refs directly or indirectly it must
 * implement IRefContainer
 */
public interface IRefContainer {

	/**
	 * Gets the number of Refs contained within this object. This number is
	 * immutable for any given instance.
	 * 
	 * @return The number of Refs in this object
	 */
	public int getRefCount();

	/**
	 * Gets a numbered Ref from within this object.
	 * 
	 * @param i Index of ref to get
	 * @return The Ref at the specified index
	 */
	public <R> Ref<R> getRef(int i);

	/**
	 * Updates all Refs in this object using the given function.
	 * 
	 * The function *must not* change the hash value of refs, in order to ensure
	 * structural integrity of modified data structures.
	 * 
	 * This is a building block for a very sneaky trick that enables use to do a lot
	 * of efficient operations on large trees of smart references.
	 * 
	 * Must return the same object if no Refs are altered.
	 */
	public <N extends IRefContainer> N updateRefs(IRefFunction func);

	/**
	 * Gets an array of child refs for this object, in the order accessible by
	 * IRefContainer.getRef
	 * 
	 * @param <R>
	 * @return Array of Refs
	 */
	@SuppressWarnings("unchecked")
	public default <R> Ref<R>[] getChildRefs() {
		int n = getRefCount();
		Ref<R>[] refs = new Ref[n];
		for (int i = 0; i < n; i++) {
			refs[i] = getRef(i);
		}
		return refs;
	}
}
