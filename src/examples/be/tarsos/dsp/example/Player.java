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


package be.tarsos.dsp.example;

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.io.File;
import java.io.IOException;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.UnsupportedAudioFileException;

import be.tarsos.dsp.AudioDispatcher;
import be.tarsos.dsp.AudioEvent;
import be.tarsos.dsp.AudioProcessor;
import be.tarsos.dsp.GainProcessor;
import be.tarsos.dsp.WaveformSimilarityBasedOverlapAdd;
import be.tarsos.dsp.WaveformSimilarityBasedOverlapAdd.Parameters;
import be.tarsos.dsp.io.jvm.AudioDispatcherFactory;
import be.tarsos.dsp.io.jvm.AudioPlayer;
import be.tarsos.dsp.util.SamplesMath;

public class Player implements AudioProcessor
{


    private final PropertyChangeSupport support = new PropertyChangeSupport(this);

    private PlayerState state;
    private File loadedFile;
    private GainProcessor gainProcessor;

    private WaveformSimilarityBasedOverlapAdd wsola;
    private AudioDispatcher dispatcher = null;

    private Thread audioThread = null;

    private double durationInSeconds = 0.0;
    private double currentTime = 0.0;
    private double pauzedAt = 0.0;

    private int totalFrames = 0;

    private final AudioProcessor beforeWSOLAProcessor;
    private final AudioProcessor afterWSOLAProcessor;

    private volatile double gain;
    private volatile double tempo;

    public Player(AudioProcessor beforeWSOLAProcessor, AudioProcessor afterWSOLAProcessor)
    {
        state = PlayerState.NO_FILE_LOADED;
        gain = 1.0;
        tempo = 1.0;
        this.beforeWSOLAProcessor = beforeWSOLAProcessor;
        this.afterWSOLAProcessor = afterWSOLAProcessor;
    }

    private Parameters currentPlayerParam(double tempo, double sampleRate)
    {
        return Parameters.musicDefaults(tempo, sampleRate);
    }

    public void load(File file)
    {
        if (state != PlayerState.NO_FILE_LOADED)
        {
            eject();
        }
        loadedFile = file;
        AudioFileFormat fileFormat;
        try
        {
            fileFormat = AudioSystem.getAudioFileFormat(loadedFile);
        } catch (UnsupportedAudioFileException | IOException e)
        {
            throw new Error(e);
        }
        final AudioFormat format = fileFormat.getFormat();
        durationInSeconds = (totalFrames = fileFormat.getFrameLength()) / format.getFrameRate();
        pauzedAt = 0;
        currentTime = 0;
        setState(PlayerState.FILE_LOADED);
    }

    public void eject()
    {
        loadedFile = null;
        stop();
        setState(PlayerState.NO_FILE_LOADED);
    }

    public void play()
    {
        if (state == PlayerState.NO_FILE_LOADED)
        {
            throw new IllegalStateException("Can not play when no file is loaded");
        } else if (state != PlayerState.PAUZED)
        {
            pauzedAt = 0.;
        }
        play(pauzedAt);
    }

    public void play(double startTime)
    {
        if (state == PlayerState.NO_FILE_LOADED)
        {
            throw new IllegalStateException("Can not play when no file is loaded");
        } else
        {
            try
            {
                final AudioFileFormat fileFormat = AudioSystem.getAudioFileFormat(loadedFile);
                final AudioFormat format = fileFormat.getFormat();

                gainProcessor = new GainProcessor(gain);
                wsola = new WaveformSimilarityBasedOverlapAdd(currentPlayerParam(tempo, format.getSampleRate()), format.getChannels());
                dispatcher = AudioDispatcherFactory.fromFile(loadedFile, wsola.getInputBufferSize(), wsola.getOverlap());

                wsola.setDispatcher(dispatcher);
                dispatcher.skip(startTime);

                dispatcher.addAudioProcessor(this);
                dispatcher.addAudioProcessor(gainProcessor);
                dispatcher.addAudioProcessor(beforeWSOLAProcessor);
                dispatcher.addAudioProcessor(wsola);
                dispatcher.addAudioProcessor(afterWSOLAProcessor);


                //dispatcher.addAudioProcessor(audioPlayer);
                final Runnable dumb = () -> {
                    final AudioPlayer audioPlayer;
                    try
                    {
                        audioPlayer = new AudioPlayer(format);
                    } catch (LineUnavailableException e)
                    {
                        throw new Error(e);
                    }
                    dispatcher.addAudioProcessor(audioPlayer);
                    dispatcher.run();
                };


                //audioThread = new Thread(dispatcher,"Audio Player Thread");
                audioThread = new Thread(dumb, "Audio Player Thread");
                audioThread.start();
                setState(PlayerState.PLAYING);
            } catch (UnsupportedAudioFileException | IOException e)
            {
                throw new Error(e);
            }
        }
    }

    public void pauze()
    {
        pauze(currentTime);
    }

    public void pauze(double pauzeAt)
    {
        if (state == PlayerState.PLAYING || state == PlayerState.PAUZED)
        {
            setState(PlayerState.PAUZED);
            dispatcher.stop();
            pauzedAt = pauzeAt;
        } else
        {
            throw new IllegalStateException("Can not pauze when nothing is playing");
        }
    }

    public void stop()
    {
        if (state == PlayerState.PLAYING || state == PlayerState.PAUZED)
        {
            setState(PlayerState.STOPPED);
            dispatcher.stop();
            audioThread.interrupt();
            audioThread = null;
            dispatcher = null;
        } else if (state != PlayerState.STOPPED)
        {
            throw new IllegalStateException("Can not stop when nothing is playing");
        }

    }

    public void setGain(double newGain)
    {
        gain = newGain;
        if (state == PlayerState.PLAYING)
        {
            gainProcessor.setGain(gain);
        }
    }

    public void setTempo(double newTempo)
    {
        tempo = newTempo;
        if (state == PlayerState.PLAYING)
        {
            wsola.setParameters(currentPlayerParam(tempo, dispatcher.getFormat().getSampleRate()));
        }
    }

    public double getDurationInSeconds()
    {
        if (state == PlayerState.NO_FILE_LOADED)
        {
            throw new IllegalStateException("No file loaded, unable to determine the duration in seconds.");
        }
        return durationInSeconds;
    }

    public int getTotalFrames()
    {
        if (state == PlayerState.NO_FILE_LOADED)
        {
            throw new IllegalStateException("No file loaded, unable to determine the duration in frames.");
        }
        return totalFrames;
    }

    private void setState(PlayerState newState)
    {
        PlayerState oldState = state;
        state = newState;
        support.firePropertyChange("state", oldState, newState);
    }

    public PlayerState getState()
    {
        return state;
    }

    public void addPropertyChangeListener(PropertyChangeListener l)
    {
        support.addPropertyChangeListener(l);
    }

    public void removePropertyChangeListener(PropertyChangeListener l)
    {
        support.removePropertyChangeListener(l);
    }


    @Override
    public boolean process(AudioEvent audioEvent)
    {
        currentTime = audioEvent.getTimeStamp();
        return true;
    }

    @Override
    public void processingFinished()
    {
        if (state == PlayerState.PLAYING)
        {
            setState(PlayerState.STOPPED);
        }
    }


    /**
     * Defines the state of the audio player.
     *
     * @author Joren Six
     */
    public static enum PlayerState
    {
        /**
         * No file is loaded.
         */
        NO_FILE_LOADED,
        /**
         * A file is loaded and ready to be played.
         */
        FILE_LOADED,
        /**
         * The file is playing
         */
        PLAYING,
        /**
         * Audio play back is paused.
         */
        PAUZED,
        /**
         * Audio play back is stopped.
         */
        STOPPED
    }
}
