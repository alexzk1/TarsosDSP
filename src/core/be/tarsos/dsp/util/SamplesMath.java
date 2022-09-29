package be.tarsos.dsp.util;

import java.util.Arrays;

//basic helping tasks for working with multichannel signal, i.e.
//when sample is the couple of numbers

public class SamplesMath
{
    public final int channelsPerSample;

    public SamplesMath(final int channelsPerSample)
    {
        this.channelsPerSample = channelsPerSample;
    }

    //copies 2 arrays, but lengths/offsets are measured in samples
    public final void samplesCopy(final float[] src, final int src_off_samples, final float[] dst,
                                  final int dst_off_samples, final int samplesCount)
    {
        System.arraycopy(src, sampleToArrayIndex(src_off_samples), dst,
                sampleToArrayIndex(dst_off_samples), sampleToArrayIndex(samplesCount));
    }

    //sets value of couple sequential samples
    public final void samplesSet(final float[] src, final int src_off_samples,
                                 final int samples_count, final float value)
    {
        Arrays.fill(src, sampleToArrayIndex(src_off_samples),
                sampleToArrayIndex(samples_count + src_off_samples), value);
    }

    //sets value of the single sample
    public final void sampleSet(final float[] src,
                                final int src_off_samples, final float value)
    {
        samplesSet(src, src_off_samples, 1, value);
    }

    //adds value to sequential samples
    public final void shiftByValue(final float[] src, final int src_off_samples,
                                   final int samples_count, final float v)
    {
        final int start = sampleToArrayIndex(src_off_samples);
        for (int i = 0, sz = sampleToArrayIndex(samples_count); i < sz; ++i)
            src[i + start] += v;
    }

    //multiplies by value sequential samples
    public final void scaleByValue(final float[] src, final int src_off_samples,
                                   final int samples_count, final float v)
    {
        final int start = sampleToArrayIndex(src_off_samples);
        for (int i = 0, sz = sampleToArrayIndex(samples_count); i < sz; ++i)
            src[i + start] *= v;
    }

    //iterates over components of 1 sample
    public final void forEachChannel(final float[] samples, final int sample_id,
                                     final IndexedCallbackWithValue<Float> callBack)
    {
        final int ai = sampleToArrayIndex((sample_id));
        for (int i = 0; i < channelsPerSample; ++i)
            callBack.callback(i, samples[ai + i]);
    }

    //iterates over channels count, just a loop which fits amount
    public final void forEachChannel(final IndexedCallback callBack)
    {
        for (int i = 0; i < channelsPerSample; ++i)
            callBack.callback(i);
    }

    //calculates average of all components into single sample
    public final float monoSample(final float[] samples, final int sample_id)
    {
        final var sum = new LambdaFinals.LambdaFloat();
        forEachChannel(samples, sample_id, (i, s) -> {
            sum.value += s;
        });
        return sum.value / channelsPerSample;
    }

    //converts sample id to array offset where array contains 1 value for 1 component of 1 sample
    public final int sampleToArrayIndex(final int sampleIndex)
    {
        return sampleIndex * channelsPerSample;
    }

    //reverse conversion, by given array offset / len calculates sample id / total
    public final int arrayIndexToSample(final int arrayIndex)
    {
        assert 0 == (arrayIndex % channelsPerSample) : "array index is not aligned to sample boundary.";
        return arrayIndex / channelsPerSample;
    }

    //finds new array length (not samples!) factored to keep samples properly
    public int arrayFactoredLength(final int originalArrayLen, final double factor)
    {
        final int samplesCount = arrayIndexToSample(originalArrayLen);
        return sampleToArrayIndex(samplesCountFactored(samplesCount, factor));
    }

    //factors samples count properly with rounding to bigger value
    public int samplesCountFactored(final int samplesCount, final double factor)
    {
        return (int) Math.round(samplesCount * factor);
    }

    //allocates new samples' buffer, copies old buffer content if given
    public float[] reallocSamples(final float[] oldSamples, final int newSamplesCount)
    {
        final float[] n = new float[sampleToArrayIndex(newSamplesCount)];
        if (oldSamples != null)
        {
            final int size = Math.min(n.length, oldSamples.length);
            System.arraycopy(oldSamples, 0, n, 0, size);
        }
        return n;
    }
}
