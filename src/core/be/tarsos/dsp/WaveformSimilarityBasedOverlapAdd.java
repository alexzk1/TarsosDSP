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


package be.tarsos.dsp;


import be.tarsos.dsp.util.LambdaFinals;
import be.tarsos.dsp.util.SamplesMath;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

/**
 * <p>
 * An overlap-add technique based on waveform similarity (WSOLA) for high
 * quality time-scale modification of speech
 * </p>
 * <p>
 * A concept of waveform similarity for tackling the problem of time-scale
 * modification of speech is proposed. It is worked out in the context of
 * short-time Fourier transform representations. The resulting WSOLA
 * (waveform-similarity-based synchronized overlap-add) algorithm produces
 * high-quality speech output, is algorithmically and computationally efficient
 * and robust, and allows for online processing with arbitrary time-scaling
 * factors that may be specified in a time-varying fashion and can be chosen
 * over a wide continuous range of values.
 * </p>
 * <p>
 * Inspired by the work soundtouch by Olli Parviainen,
 * http://www.surina.net/soundtouch, especially the TDStrech.cpp file.
 * </p>
 *
 * @author Joren Six
 * @author Olli Parviainen
 */
public class WaveformSimilarityBasedOverlapAdd implements AudioProcessor
{
    private int seekWindowLength = 0;
    private int seekLength = 0;
    private int overlapLength = 0;

    private volatile float output2input_rate = 1.f;

    private float[] pMidBuffer = null;
    private float[] pRefMidBuffer = null;
    private float[] outputFloatBuffer = null;

    private int intskip = 0;
    private int sampleReq = 0;

    private final AtomicReference<AudioDispatcher> dispatcher = new AtomicReference<>(null);

    private final AtomicReference<Parameters> newParameters = new AtomicReference<>(null);


    /**
     * Create a new instance based on algorithm parameters for a certain audio format.
     *
     * @param params The parameters for the algorithm.
     */
    public WaveformSimilarityBasedOverlapAdd(Parameters params, final int channelsPerSample)
    {
        setParameters(params);

        //todo: this is 1 time use object, because i quit attempts to imagine fix for cross - referenced
        //creation of dispatcher and this class

        applyNewParameters(new SamplesMath(channelsPerSample));
    }

    public void setParameters(final Parameters params)
    {
        newParameters.set(params);
    }

    public void setDispatcher(final AudioDispatcher newDispatcher)
    {
        this.dispatcher.set(newDispatcher);
    }

    private boolean applyNewParameters(final SamplesMath samplesMath)
    {
        boolean result = false;
        final Parameters params = newParameters.getAndSet(null);

        result = (null != params);
        if (result)
        {
            output2input_rate = 1.f / (float) params.getTempo();
            final int oldOverlapLength = overlapLength;
            //measured in SAMPLES
            final double pRate = params.getSampleRate() / 1000;
            overlapLength = (int) (pRate * params.getOverlapMs());
            seekWindowLength = (int) (pRate * params.getSequenceMs());
            seekLength = (int) (pRate * params.getSeekWindowMs());

            //Fixme 2022, Alex: I think it should be OR instead AND here
            //with AND it should happen out of bounds when array remains old sized which I saw already
            //if (overlapLength > oldOverlapLength && pMidBuffer == null)
            if (overlapLength > oldOverlapLength || pMidBuffer == null)
            {
                pMidBuffer = samplesMath.reallocSamples(pMidBuffer, overlapLength);
                pRefMidBuffer = samplesMath.reallocSamples(pRefMidBuffer, overlapLength);
                //System.out.println("New overlapLength (in samples): " + overlapLength);
            }

            final double nominalSkip = params.getTempo() * (seekWindowLength - overlapLength);
            intskip = (int) (nominalSkip + 0.5);

            sampleReq = Math.max(intskip + overlapLength, seekWindowLength) + seekLength;
            outputFloatBuffer = samplesMath.reallocSamples(outputFloatBuffer, getOutputBufferSize());
        }
        return result;
    }

    //in SAMPLES!!!! (in array elements do * channelsCount)
    public int getInputBufferSize()
    {
        return sampleReq;
    }

    //in SAMPLES!!!! (in array elements do * channelsCount)
    private int getOutputBufferSize()
    {
        return seekWindowLength - overlapLength;
    }

    //in SAMPLES!!!! (in array elements do * channelsCount)
    public int getOverlap()
    {
        return sampleReq - intskip;
    }


    /**
     * Overlaps the sample in output with the samples in input.
     *
     * @param output The output buffer.
     * @param input  The input buffer.
     */
    private void overlap(final float[] output, final int outputOffset, final float[] input, final int inputOffset,
                         final SamplesMath samplesMath)
    {
        final var i = new LambdaFinals.LambdaInt();
        for (i.value = 0; i.value < overlapLength; ++i.value)
        {
            final int itemp = overlapLength - i.value;
            final int off_o = samplesMath.sampleToArrayIndex(i.value + outputOffset);
            final int off_i = samplesMath.sampleToArrayIndex(i.value + inputOffset);
            final int off_m = samplesMath.sampleToArrayIndex(i.value);
            samplesMath.forEachChannel((c) ->
                    output[off_o + c] = (input[off_i + c] * i.value + pMidBuffer[off_m + c] * itemp) / overlapLength);
        }
    }


    /**
     * Seeks for the optimal overlap-mixing position.
     * <p>
     * The best position is determined as the position where the two overlapped
     * sample sequences are 'most alike', in terms of the highest
     * cross-correlation value over the overlapping period
     *
     * @param inputBuffer The input buffer
     * @param position    The position where to start the seek operation, in the input buffer in SAMPLES.
     * @return The best position.
     */
    private int seekBestOverlapPosition(float[] inputBuffer, int position, final SamplesMath samplesMath)
    {

        // Slopes the amplitude of the 'midBuffer' samples
        precalcCorrReference(samplesMath);

        double bestCorrelation = -10;
        int bestOffset = 0;

        // Scans for the best correlation value by testing each possible
        // position
        // over the permitted range.
        //measured in SAMPLES
        for (int tempOffset = 0; tempOffset < seekLength; ++tempOffset)
        {
            final int comparePosition = position + tempOffset;

            // Calculates correlation value for the mixing position
            // corresponding
            // to 'tempOffset'
            double currentCorrelation = calcCrossCorr(pRefMidBuffer, inputBuffer, comparePosition, samplesMath);
            // heuristic rule to slightly favor values close to mid of the
            // range
            final double tmp = (double) (2 * tempOffset - seekLength) / seekLength;
            currentCorrelation = ((currentCorrelation + 0.1) * (1.0 - 0.25 * tmp * tmp));

            // Checks for the highest correlation value
            if (currentCorrelation > bestCorrelation)
            {
                bestCorrelation = currentCorrelation;
                bestOffset = tempOffset;
            }
        }

        return bestOffset; //IN SAMPLES!!!
    }

    /**
     * Slopes the amplitude of the 'midBuffer' samples so that cross correlation
     * is faster to calculate. Why is this faster?
     */
    void precalcCorrReference(final SamplesMath samplesMath)
    {
        for (int i = 0; i < overlapLength; ++i)
        {
            final int off = samplesMath.sampleToArrayIndex(i);
            final float temp = i * (overlapLength - i);
            samplesMath.forEachChannel((j) -> {
                final int ofj = off + j;
                pRefMidBuffer[ofj] = pMidBuffer[ofj] * temp;
            });
        }
    }

    double calcCrossCorr(final float[] mixingPos, final float[] compare, final int offset, final SamplesMath samplesMath)
    {
        double corr = 0;
        double norm = 0;
        //FIXME: Alex 2022: why i = 1 ? Why don't we check 0th sample?
        //for (int i = 1; i < overlapLength; ++i)
        for (int i = 0; i < overlapLength; ++i)
        {
            final float mono_mp = samplesMath.monoSample(mixingPos, i);
            final float mono_cmp = samplesMath.monoSample(compare, i + offset);
            corr += mono_mp * mono_cmp;
            norm += mono_mp * mono_mp;
        }
        // To avoid division by zero.
        if (norm < 1e-8)
        {
            norm = 1.0;
        }
        return corr / Math.pow(norm, 0.5);
    }

    @Override
    public boolean process(AudioEvent audioEvent)
    {
        final var samplesMath = audioEvent.getSamplesMath();
        final float[] audioFloatBuffer = audioEvent.getFloatBuffer();
        assert audioFloatBuffer.length == getInputBufferSize();

        //Search for the best overlapping position. IN SAMPLES!!!
        final int offset = seekBestOverlapPosition(audioFloatBuffer, 0, samplesMath);

        // Mix the samples in the 'inputBuffer' at position of 'offset' with the
        // samples in 'midBuffer' using sliding overlapping
        // ... first partially overlap with the end of the previous sequence
        // (that's in 'midBuffer')
        overlap(outputFloatBuffer, 0, audioFloatBuffer, offset, samplesMath);

        //copy sequence samples from input to output
        final int sequenceLength = seekWindowLength - 2 * overlapLength;
        samplesMath.samplesCopy(audioFloatBuffer, offset + overlapLength,
                outputFloatBuffer, overlapLength, sequenceLength);

        // Copies the end of the current sequence from 'inputBuffer' to
        // 'midBuffer' for being mixed with the beginning of the next
        // processing sequence and so on
        samplesMath.samplesCopy(audioFloatBuffer, offset + sequenceLength + overlapLength,
                pMidBuffer, 0, overlapLength);

        assert outputFloatBuffer.length == getOutputBufferSize();

        audioEvent.setFloatBuffer(outputFloatBuffer);
        audioEvent.setOverlap(0);
        audioEvent.output2input_ratio = output2input_rate;

        if (applyNewParameters(samplesMath))
            dispatcher.get().setStepSizeAndOverlap(getInputBufferSize(), getOverlap());

        return true;
    }

    @Override
    public void processingFinished()
    {
        // NOOP
    }


    /**
     * An object to encapsulate some of the parameters for
     * WSOLA, together with a couple of practical helper functions.
     *
     * @author Joren Six
     */
    public static class Parameters
    {
        private final int sequenceMs;
        private final int seekWindowMs;
        private final int overlapMs;

        private final double tempo;
        private final double sampleRate;

        /**
         * @param tempo           The tempo change 1.0 means unchanged, 2.0 is + 100% , 0.5
         *                        is half of the speed.
         * @param sampleRate      The sample rate of the audio 44.1kHz is common.
         * @param newSequenceMs   Length of a single processing sequence, in milliseconds.
         *                        This determines to how long sequences the original sound
         *                        is chopped in the time-stretch algorithm.
         *                        <p>
         *                        The larger this value is, the lesser sequences are used in
         *                        processing. In principle a bigger value sounds better when
         *                        slowing down tempo, but worse when increasing tempo and
         *                        vice versa.
         *                        <p>
         *                        Increasing this value reduces computational burden & vice
         *                        versa.
         * @param newSeekWindowMs Seeking window length in milliseconds for algorithm that
         *                        finds the best possible overlapping location. This
         *                        determines from how wide window the algorithm may look for
         *                        an optimal joining location when mixing the sound
         *                        sequences back together.
         *                        <p>
         *                        The bigger this window setting is, the higher the
         *                        possibility to find a better mixing position will become,
         *                        but at the same time large values may cause a "drifting"
         *                        artifact because consequent sequences will be taken at
         *                        more uneven intervals.
         *                        <p>
         *                        If there's a disturbing artifact that sounds as if a
         *                        constant frequency was drifting around, try reducing this
         *                        setting.
         *                        <p>
         *                        Increasing this value increases computational burden &
         *                        vice versa.
         * @param newOverlapMs    Overlap length in milliseconds. When the chopped sound
         *                        sequences are mixed back together, to form a continuous
         *                        sound stream, this parameter defines over how long period
         *                        the two consecutive sequences are let to overlap each
         *                        other.
         *                        <p>
         *                        This shouldn't be that critical parameter. If you reduce
         *                        the DEFAULT_SEQUENCE_MS setting by a large amount, you
         *                        might wish to try a smaller value on this.
         *                        <p>
         *                        Increasing this value increases computational burden &
         *                        vice versa.
         */
        public Parameters(double tempo, double sampleRate, int newSequenceMs, int newSeekWindowMs, int newOverlapMs)
        {
            this.tempo = tempo;
            this.sampleRate = sampleRate;
            this.overlapMs = newOverlapMs;
            this.seekWindowMs = newSeekWindowMs;
            this.sequenceMs = newSequenceMs;
        }

        public static Parameters speechDefaults(double tempo, double sampleRate)
        {
            int sequenceMs = 40;
            int seekWindowMs = 15;
            int overlapMs = 12;
            return new Parameters(tempo, sampleRate, sequenceMs, seekWindowMs, overlapMs);
        }

        public static Parameters musicDefaults(double tempo, double sampleRate)
        {
            int sequenceMs = 82;
            int seekWindowMs = 28;
            int overlapMs = 12;
            return new Parameters(tempo, sampleRate, sequenceMs, seekWindowMs, overlapMs);
        }

        public static Parameters slowdownDefaults(double tempo, double sampleRate)
        {
            int sequenceMs = 100;
            int seekWindowMs = 35;
            int overlapMs = 20;
            return new Parameters(tempo, sampleRate, sequenceMs, seekWindowMs, overlapMs);
        }

        public static Parameters automaticDefaults(double tempo, double sampleRate)
        {
            double tempoLow = 0.5; // -50% speed
            double tempoHigh = 2.0; // +100% speed

            double sequenceMsLow = 125; //ms
            double sequenceMsHigh = 50; //ms
            double sequenceK = ((sequenceMsHigh - sequenceMsLow) / (tempoHigh - tempoLow));
            double sequenceC = sequenceMsLow - sequenceK * tempoLow;

            double seekLow = 25;// ms
            double seekHigh = 15;// ms
            double seekK = ((seekHigh - seekLow) / (tempoHigh - tempoLow));
            double seekC = seekLow - seekK * seekLow;

            int sequenceMs = (int) (sequenceC + sequenceK * tempo + 0.5);
            int seekWindowMs = (int) (seekC + seekK * tempo + 0.5);
            int overlapMs = 12;
            return new Parameters(tempo, sampleRate, sequenceMs, seekWindowMs, overlapMs);
        }

        public double getOverlapMs()
        {
            return overlapMs;
        }

        public double getSequenceMs()
        {
            return sequenceMs;
        }

        public double getSeekWindowMs()
        {
            return seekWindowMs;
        }

        public double getSampleRate()
        {
            return sampleRate;
        }

        public double getTempo()
        {
            return tempo;
        }
    }
}
