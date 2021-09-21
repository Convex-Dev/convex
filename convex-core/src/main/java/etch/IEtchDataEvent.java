package etch;

import convex.core.data.AArrayBlob;
import convex.core.data.ABlob;

public interface IEtchDataEvent {

    /**
     * Called on each start of a found hash index value. The hash can have 1+ more index values
     */
    public void onIndexValue(int value);

    /**
     * Called on the start of a walk along an index block starting at the given position.
     *
     * @param indexPosition Position of the index block
     *
     */
    public void onWalkIndex(long indexPosition);

    /**
     * Called when a data item is found in the Etch database.
     *
     * @param keyPosition Position of the key
     * @param key The hash key that is stored
     * @param dataPosition Position of the data record
     * @param flags Flags for the data
     * @param memorySize Amount of memory size to create
     * @param data The actual data stored in Etch db
     * @param length The length of the data
     *
     */
    public void onData(long keyPosition, AArrayBlob key, long dataPosition, byte flags, long memorySize, ABlob data, short length);

}
