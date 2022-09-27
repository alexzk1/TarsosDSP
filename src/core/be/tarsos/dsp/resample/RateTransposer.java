/*
 *      _______                       _____   _____ _____
 *     |__   __|                     |  __ \ / ____|  __ \
 *        | | __ _ _ __ ___  ___  ___| |  | | (___ | |__) |
 *        | |/ _` | '__/ __|/ _ \/ __| |  | |\___ \|  ___/
 *        | | (_| | |  \__ \ (_) \__ \ |__| |____) | |
 *        |_|\__,_|_|  |___/\___/|___/_____/|_____/|_|
 *
 * -------------------------------------------------------------
 *
 * TarsosDSP is developed by Joren Six at IPEM, University Ghent
 *
 * -------------------------------------------------------------
 *
 *  Info: http://0110.be/tag/TarsosDSP
 *  Github: https://github.com/JorenSix/TarsosDSP
 *  Releases: http://0110.be/releases/TarsosDSP/
 *
 *  TarsosDSP includes modified source code by various authors,
 *  for credits and info, see README.
 *
 */

package be.tarsos.dsp.resample;

import be.tarsos.dsp.AudioEvent;
import be.tarsos.dsp.AudioProcessor;
import be.tarsos.dsp.util.SamplesMath;


/**
 * Sample rate transposer. Changes sample rate by using  interpolation
 * <p>
 * Together with the time stretcher this can be used for pitch shifting.
 *
 * @author Joren Six
 */
public class RateTransposer implements AudioProcessor
{

    private volatile double factor = 1.;
    private final Object factorMutex = new Object();

    private Resampler r = null;

    /**
     * Create a new sample rate transposer. The factor determines the new sample
     * rate. E.g. 0.5 is half the sample rate, 1.0 does not change a thing and
     * 2.0 doubles the samplerate. If the samples are played at the original
     * speed the pitch doubles (0.5), does not change (1.0) or halves (0.5)
     * respectively. Playback length follows the same rules, obviously.
     *
     * @param factor Determines the new sample rate. E.g. 0.5 is half the sample
     *               rate, 1.0 does not change a thing and 2.0 doubles the sample
     *               rate. If the samples are played at the original speed the
     *               pitch doubles (0.5), does not change (1.0) or halves (0.5)
     *               respectively. Playback length follows the same rules,
     *               obviously.
     */
    public RateTransposer(final double factor)
    {
        this.factor = factor;
    }

    public RateTransposer()
    {
    }

    public void setFactor(final double tempo)
    {
        synchronized (factorMutex)
        {
            this.factor = tempo;
        }
    }

    private float[] out = new float[0];

    @Override
    public boolean process(AudioEvent audioEvent)
    {
        final double factor;
        synchronized (factorMutex)
        {
            factor = this.factor;
        }

        if (null == r)
        {
            r = new Resampler(false, 0.1, 4.0, audioEvent.getSamplesMath());
        }
        final float[] src = audioEvent.getFloatBuffer();
        final int required_size = audioEvent.getSamplesMath().arrayFactoredLength(src.length, factor);

        //can't use < (less) because array goes to another processor and that checks full length
        //so it must be exact size here
        if (out.length != required_size)
            out = new float[required_size];

        r.process(factor, src, 0, src.length, false, out, 0, required_size);
        //The size of the output buffer changes (according to factor).
        audioEvent.setFloatBuffer(out);
        //Update overlap offset to match new buffer size
        audioEvent.setOverlap(audioEvent.getSamplesMath().samplesCountFactored(audioEvent.getOverlap(), factor));

        return true;
    }

    @Override
    public void processingFinished()
    {
    }
}
