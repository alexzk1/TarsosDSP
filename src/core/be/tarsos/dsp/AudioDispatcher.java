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

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import be.tarsos.dsp.io.TarsosDSPAudioFloatConverter;
import be.tarsos.dsp.io.TarsosDSPAudioFormat;
import be.tarsos.dsp.io.TarsosDSPAudioInputStream;
import be.tarsos.dsp.util.SamplesMath;


/**
 * This class plays a file and sends float arrays to registered AudioProcessor
 * implementors. This class can be used to feed FFT's, pitch detectors, audio players, ...
 * Using a (blocking) audio player it is even possible to synchronize execution of
 * AudioProcessors and sound. This behavior can be used for visualization.
 *
 * @author Joren Six
 */
public class AudioDispatcher extends DispatchCollection implements Runnable
{


    /**
     * Log messages.
     */
    private static final Logger LOG = Logger.getLogger(AudioDispatcher.class.getName());

    /**
     * The audio stream (in bytes), conversion to float happens at the last
     * moment.
     */
    private final TarsosDSPAudioInputStream audioInputStream;

    /**
     * This buffer is reused again and again to store audio data using the float
     * data type.
     */
    private float[] audioFloatBuffer = null;

    /**
     * This buffer is reused again and again to store audio data using the byte
     * data type.
     */
    private byte[] audioByteBuffer = null;

    /**
     * Converter converts an array of floats to an array of bytes (and vice
     * versa).
     */
    private final TarsosDSPAudioFloatConverter converter;

    private final TarsosDSPAudioFormat format;

    /**
     * The floatOverlap: the number of elements that are copied in the buffer
     * from the previous buffer. Overlap should be smaller (strict) than the
     * buffer size and can be zero. Defined in number of samples.
     */
    private int samplesOverlap = 0, samplesStepSize = 0;

    /**
     * The overlap and stepsize defined not in samples but in bytes. So it
     * depends on the bit depth. Since the int datatype is used only 8,16,24,...
     * bits or 1,2,3,... bytes are supported.
     */
    private int byteOverlap = 0, byteStepSize = 0;


    /**
     * The number of bytes to skip before processing starts.
     */
    private long bytesToSkip = 0L;

    /**
     * Position in the stream in bytes. e.g. if 44100 bytes are processed and 16
     * bits per frame are used then you are 0.5 seconds into the stream.
     */
    private long bytesProcessed = 0L;


    /**
     * The audio event that is send through the processing chain.
     */
    private final AudioEvent audioEvent;

    /**
     * If true the dispatcher stops dispatching audio.
     */
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    /**
     * If true then the first buffer is only filled up to buffer size - hop size
     * E.g. if the buffer is 2048 and the hop size is 48 then you get 2000 times
     * zero 0 and 48 actual audio samples. During the next iteration you get
     * mostly zeros and 96 samples.
     */
    private boolean zeroPadFirstBuffer = false;

    /**
     * If true then the last buffer is zero padded. Otherwise the buffer is
     * shortened to the remaining number of samples. If false then the audio
     * processors must be prepared to handle shorter audio buffers.
     */
    private boolean zeroPadLastBuffer = true;

    private final SamplesMath samplesMathInput;

    /**
     * Create a new dispatcher from a stream.
     *
     * @param stream          The stream to read data from.
     * @param audioBufferSize The size of the buffer defines how much samples are processed
     *                        in one step. Common values are 1024,2048.
     * @param bufferOverlap   How much consecutive buffers overlap (in samples). Half of the
     *                        AudioBufferSize is common (512, 1024) for an FFT.
     */

    public AudioDispatcher(final TarsosDSPAudioInputStream stream, final int audioBufferSize, final int bufferOverlap)
    {
        // The copy on write list allows concurrent modification of the list while
        // it is iterated. A nice feature to have when adding AudioProcessors while
        // the AudioDispatcher is running.
        audioInputStream = stream;
        format = audioInputStream.getFormat();
        audioEvent = new AudioEvent(format);

        //that will be just shortcut for internal use
        samplesMathInput = audioEvent.getSamplesMath();

        setStepSizeAndOverlap(audioBufferSize, bufferOverlap);
        audioEvent.setFloatBuffer(audioFloatBuffer);
        audioEvent.setOverlap(samplesOverlap);

        converter = TarsosDSPAudioFloatConverter.getConverter(format);

        stopped.set(false);
    }

    /**
     * Skip a number of seconds before processing the stream.
     *
     * @param seconds
     */
    public void skip(double seconds)
    {
        bytesToSkip = Math.round(seconds * format.getSampleRate()) * format.getFrameSize();
    }

    /**
     * Set a new step size and overlap size. Both in number of samples. Watch
     * out with this method: it should be called after a batch of samples is
     * processed, not during.
     *
     * @param audioBufferSize The size of the buffer defines how much samples are processed
     *                        in one step. Common values are 1024,2048. IN SAMPLES
     * @param bufferOverlap   How much consecutive buffers overlap (in samples). Half of the
     *                        AudioBufferSize is common (512, 1024) for an FFT.
     */
    public void setStepSizeAndOverlap(final int audioBufferSize, final int bufferOverlap)
    {
        audioFloatBuffer = samplesMathInput.reallocSamples(null, audioBufferSize);
        samplesOverlap = bufferOverlap; // in samples
        samplesStepSize = audioBufferSize - samplesOverlap; // in samples

        audioByteBuffer = new byte[audioBufferSize * format.getFrameSize()];
        byteOverlap = samplesOverlap * format.getFrameSize();
        byteStepSize = samplesStepSize * format.getFrameSize();
    }

    /**
     * if zero pad is true then the first buffer is only filled up to  buffer size - hop size
     * E.g. if the buffer is 2048 and the hop size is 48 then you get 2000x0 and 48 filled audio samples
     *
     * @param zeroPadFirstBuffer true if the buffer should be zeroPadFirstBuffer, false otherwise.
     */
    public void setZeroPadFirstBuffer(boolean zeroPadFirstBuffer)
    {
        this.zeroPadFirstBuffer = zeroPadFirstBuffer;
    }

    /**
     * If zero pad last buffer is true then the last buffer is filled with zeros until the normal amount
     * of elements are present in the buffer. Otherwise the buffer only contains the last elements and no zeros.
     * By default it is set to true.
     *
     * @param zeroPadLastBuffer
     */
    public void setZeroPadLastBuffer(boolean zeroPadLastBuffer)
    {
        this.zeroPadLastBuffer = zeroPadLastBuffer;
    }


    public void run()
    {
        final Supplier<Integer> reader = () -> {
            int local_bytesRead = 0;

            audioEvent.setBytesProcessed(bytesProcessed);
            try
            {
                //needed to get correct time info when skipping first x seconds
                local_bytesRead = readNextAudioBlock();
            } catch (IOException e)
            {
                String message = "Error while reading audio input stream: " + e.getMessage();
                LOG.warning(message);
                throw new Error(message);
            }
            audioEvent.setOverlap(samplesOverlap);

            return local_bytesRead;
        };

        bytesProcessed = 0;
        skipToStart();

        for (int bytesRead = reader.get(); bytesRead > 0 && !stopped.get(); bytesRead = reader.get())
        {
            forEachAudioProcessor(processor -> processor.process(audioEvent));
            bytesProcessed += bytesRead;
        }

        stop();
    }

    private void skipToStart()
    {
        if (bytesProcessed < bytesToSkip)
        {
            long skipped = 0L;
            try
            {
                skipped = audioInputStream.skip(bytesToSkip);
                if (skipped != bytesToSkip)
                {
                    throw new IOException();
                }
                bytesProcessed += bytesToSkip;
            } catch (IOException e)
            {
                String message = String.format("Did not skip the expected amount of bytes,  %d skipped, %d expected!", skipped, bytesToSkip);
                LOG.warning(message);
                throw new Error(message);
            }
        }
    }

    /**
     * Stops dispatching audio data.
     */
    public void stop()
    {
        if (!stopped.getAndSet(true))
        {
            forEachAudioProcessor(processor -> {
                processor.processingFinished();
                return true;
            });
            try
            {
                audioInputStream.close();
            } catch (IOException e)
            {
                LOG.log(Level.SEVERE, "Closing audio stream error.", e);
            }
        }
    }

    /**
     * Reads the next audio block. It tries to read the number of bytes defined
     * by the audio buffer size minus the overlap. If the expected number of
     * bytes could not be read either the end of the stream is reached or
     * something went wrong.
     * <p>
     * The behavior for the first and last buffer is defined by their corresponding the zero pad settings. The method also handles the case if
     * the first buffer is also the last.
     *
     * @return The number of bytes read.
     * @throws IOException When something goes wrong while reading the stream. In
     *                     particular, an IOException is thrown if the input stream has
     *                     been closed.
     */
    private int readNextAudioBlock() throws IOException
    {
        assert samplesOverlap < audioFloatBuffer.length;

        // Is this the first buffer?
        final boolean isFirstBuffer = (bytesProcessed <= bytesToSkip);

        final int offsetInBytes;

        final int offsetInSamples;

        final int bytesToRead;
        //Determine the amount of bytes to read from the stream
        if (isFirstBuffer && !zeroPadFirstBuffer)
        {
            //If this is the first buffer and we do not want to zero pad the
            //first buffer then read a full buffer
            bytesToRead = audioByteBuffer.length;
            // With an offset in bytes of zero;
            offsetInBytes = 0;
            offsetInSamples = 0;
        } else
        {
            //In all other cases read the amount of bytes defined by the step size
            bytesToRead = byteStepSize;
            offsetInBytes = byteOverlap;
            offsetInSamples = samplesOverlap;
        }

        //Shift the audio information using array copy since it is probably faster than manually shifting it.
        // No need to do this on the first buffer
        if (!isFirstBuffer && audioFloatBuffer.length == (samplesOverlap + samplesStepSize) * format.getChannels())
        {
            samplesMathInput.samplesCopy(audioFloatBuffer, samplesStepSize, audioFloatBuffer, 0, samplesOverlap);
        }

        // Is the end of the stream reached?
        boolean endOfStream = false;

        // Always try to read the 'bytesToRead' amount of bytes.
        // unless the stream is closed (stopped is true) or no bytes could be read during one iteration

        int totalBytesRead = 0;
        while (!stopped.get() && !endOfStream && totalBytesRead < bytesToRead)
        {
            // The amount of bytes read from the stream during one iteration.
            int bytesRead = -1;
            try
            {
                bytesRead = audioInputStream.read(audioByteBuffer, offsetInBytes + totalBytesRead, bytesToRead - totalBytesRead);
            } catch (IndexOutOfBoundsException ignored)
            {
                // The pipe decoder generates an out of bounds if end
                // of stream is reached. Ugly hack...
            }
            if (bytesRead == -1)
            {
                // The end of the stream is reached if the number of bytes read during this iteration equals -1
                endOfStream = true;
            } else
            {
                // Otherwise add the number of bytes read to the total
                totalBytesRead += bytesRead;
            }
        }

        if (endOfStream)
        {
            // Could not read a full buffer from the stream, there are two options:
            if (zeroPadLastBuffer)
            {
                //Make sure the last buffer has the same length as all other buffers and pad with zeros
                Arrays.fill(audioByteBuffer, offsetInBytes + totalBytesRead, audioByteBuffer.length, (byte) 0);//
                converter.toFloatArray(audioByteBuffer, offsetInBytes, audioFloatBuffer,
                        samplesMathInput.sampleToArrayIndex(offsetInSamples), samplesMathInput.sampleToArrayIndex(samplesStepSize));
            } else
            {
                // Send a smaller buffer through the chain.
                final byte[] audioByteBufferContent = audioByteBuffer;
                audioByteBuffer = new byte[offsetInBytes + totalBytesRead];
                System.arraycopy(audioByteBufferContent, 0, audioByteBuffer, 0, audioByteBuffer.length);

                final int totalSamplesRead = totalBytesRead / format.getFrameSize();
                audioFloatBuffer = samplesMathInput.reallocSamples(null,
                        offsetInSamples + totalSamplesRead);

                converter.toFloatArray(audioByteBuffer, offsetInBytes, audioFloatBuffer,
                        samplesMathInput.sampleToArrayIndex(offsetInSamples), samplesMathInput.sampleToArrayIndex(totalSamplesRead));
            }
        } else if (bytesToRead == totalBytesRead)
        {
            // The expected amount of bytes have been read from the stream.
            if (isFirstBuffer && !zeroPadFirstBuffer)
            {
                converter.toFloatArray(audioByteBuffer, 0, audioFloatBuffer, 0, audioFloatBuffer.length);
            } else
            {
                converter.toFloatArray(audioByteBuffer, offsetInBytes, audioFloatBuffer,
                        samplesMathInput.sampleToArrayIndex(offsetInSamples), samplesMathInput.sampleToArrayIndex(samplesStepSize));
            }
        } else if (!stopped.get())
        {
            // If the end of the stream has not been reached and the number of bytes read is not the
            // expected amount of bytes, then we are in an invalid state;
            throw new IOException(String.format("The end of the audio stream has not been reached and the number of bytes read (%d) is not equal " + "to the expected amount of bytes(%d).", totalBytesRead, bytesToRead));
        }


        // Makes sure AudioEvent contains correct info.
        audioEvent.setFloatBuffer(audioFloatBuffer);
        audioEvent.setOverlap(offsetInSamples);

        return totalBytesRead;
    }

    public TarsosDSPAudioFormat getFormat()
    {
        return format;
    }

    /**
     * @return The currently processed number of seconds.
     */
    public float secondsProcessed()
    {
        final int sampleByteSize = format.getFrameSize();
        final int samplesProcessed = (int) (bytesProcessed / sampleByteSize);
        return samplesProcessed / format.getSampleRate();
    }

    public void setAudioFloatBuffer(float[] audioBuffer)
    {
        audioFloatBuffer = audioBuffer;
    }

    /**
     * @return True if the dispatcher is stopped or the end of stream has been reached.
     */
    public boolean isStopped()
    {
        return stopped.get();
    }

}
