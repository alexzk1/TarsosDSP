package be.tarsos.dsp.util;

//used to make loops callbacks
public interface IndexedCallbackWithValue<T>
{
    void callback(final int index, final T value);
}
