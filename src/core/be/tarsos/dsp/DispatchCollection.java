package be.tarsos.dsp;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class DispatchCollection
{
    /**
     * A list of registered audio processors. The audio processors are
     * responsible for actually doing the digital signal processing
     */
    private final List<AudioProcessor> audioProcessors = new CopyOnWriteArrayList<>();

    /**
     * Adds an AudioProcessor to the chain of processors.
     *
     * @param audioProcessor The AudioProcessor to add.
     */
    public void addAudioProcessor(final AudioProcessor audioProcessor)
    {
        if (audioProcessor != null)
            audioProcessors.add(audioProcessor);
    }

    /**
     * Removes an AudioProcessor to the chain of processors and calls its <code>processingFinished</code> method.
     *
     * @param audioProcessor The AudioProcessor to remove.
     */
    public void removeAudioProcessor(final AudioProcessor audioProcessor)
    {
        audioProcessors.remove(audioProcessor);
        audioProcessor.processingFinished();
    }

    public boolean forEachAudioProcessor(final ICallback callback)
    {
        boolean res = true;
        for (final AudioProcessor processor : audioProcessors)
            if (!(res = callback.process(processor)))
                break;
        return res;
    }

    protected interface ICallback
    {
        //return false to break the loop
        boolean process(final AudioProcessor ap);
    }
}
