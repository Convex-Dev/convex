package etch;

import convex.core.data.AArrayBlob;
import convex.core.data.ABlob;

public interface IEtchDataEvent {

    public void onIndexValue(int value);
    public void onWalkIndex(long indexPosition);
    public void onData(long keyPosition, AArrayBlob key, long dataPosition, byte flags, long memorySize, ABlob data, short length);

}
