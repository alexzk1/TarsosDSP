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

import java.awt.*;
import java.io.File;
import java.io.Serial;
import java.lang.reflect.InvocationTargetException;
import java.util.Locale;
import java.util.Objects;


import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.JSplitPane;
import javax.swing.SwingUtilities;
import javax.swing.border.TitledBorder;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;

import be.tarsos.dsp.AudioEvent;
import be.tarsos.dsp.AudioProcessor;
import be.tarsos.dsp.example.Player.PlayerState;
import be.tarsos.dsp.util.fft.FFT;

public class AdvancedAudioPlayer extends JFrame {

    /**
     *
     */
    @Serial
    private static final long serialVersionUID = -4000269621209901229L;

    private final static double slider_total_units = 1000.0;

    private JSlider gainSlider;
    private JSlider tempoSlider;
    private JSlider positionSlider;

    private JButton playButton;
    private JButton stopButton;
    private JButton pauzeButton;
    private JLabel progressLabel;
    private JLabel totalLabel;

    private JFileChooser fileChooser;


    private void setPosSlider(final double progress) {
        //enforcing to gui thread
        EventQueue.invokeLater(() -> {
            if (!positionSlider.getValueIsAdjusting()) {
                final int v = (int) (progress * slider_total_units);
                positionSlider.setValue(v);
            }
        });
    }

    private void setPosSlider(final double progress, final double timeStamp) {
        //enforcing to gui thread
        EventQueue.invokeLater(() -> {
            if (!positionSlider.getValueIsAdjusting()) {
                final int v = (int) (progress * slider_total_units);
                positionSlider.setValue(v);
                showTimestamp(timeStamp);
            }
        });
    }

    private void showTimestamp(final double timeStamp) {
        //be sure you call this from GUI thread!!!! DSP callbacks ARE NOT GUI!!!
        setProgressLabelText(timeStamp, player.getDurationInSeconds());
    }

    final Player player;

    final SpectrogramPanel panel = new SpectrogramPanel();

    final AudioProcessor processor = new AudioProcessor() {

        @Override
        public boolean process(final AudioEvent audioEvent) {
            final double timeStamp = audioEvent.getTimeStamp();
            final double progress = (double) audioEvent.framesProcessed() / (double) player.getTotalFrames();
            setPosSlider(progress, timeStamp);
            return true;
        }

        @Override
        public void processingFinished() {
        }
    };


    public AdvancedAudioPlayer() {
        this.setLayout(new BorderLayout());
        this.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        this.setTitle("Advanced Audio AdvancedAudioPlayer (AAP)");

        JPanel subPanel = new JPanel(new GridLayout(0, 1));

        subPanel.add(createGainPanel());
        subPanel.add(createTempoPanel());
        subPanel.add(createProgressPanel());
        subPanel.add(createButtonPanel());

        this.add(new JSplitPane(JSplitPane.VERTICAL_SPLIT, subPanel, createSpectrogramPanel()));

        player = new Player(processor, fftProcessor);
        player.addPropertyChangeListener(arg0 -> {
            if (Objects.equals(arg0.getPropertyName(), "state")) {
                PlayerState newState = (PlayerState) arg0.getNewValue();
                reactToPlayerState(newState);
            }
        });
        reactToPlayerState(player.getState());
    }

    private Component createSpectrogramPanel() {
        return panel;
    }

    private void reactToPlayerState(PlayerState newState) {
        final boolean loaded = newState != PlayerState.NO_FILE_LOADED;
        positionSlider.setEnabled(loaded);
        playButton.setEnabled(newState != PlayerState.PLAYING && loaded);
        pauzeButton.setEnabled(newState == PlayerState.PLAYING);
        stopButton.setEnabled(newState == PlayerState.PLAYING || newState == PlayerState.PAUZED);

        if (newState == PlayerState.STOPPED || newState == PlayerState.FILE_LOADED) {
            setPosSlider( 0, 0);
        }
    }

    public String formattedToString(final double seconds) {
        final int minutes = (int) (seconds / 60);
        final int completeSeconds = (int) seconds - (minutes * 60);
        final int hundred = (int) ((seconds - (int) seconds) * 100);
        return String.format(Locale.US, "%02d:%02d:%02d", minutes, completeSeconds, hundred);
    }

    private JComponent createProgressPanel() {
        positionSlider = new JSlider(0, (int) slider_total_units);
        positionSlider.setValue(0);
        positionSlider.setPaintLabels(false);
        positionSlider.setPaintTicks(false);
        positionSlider.setEnabled(false);

        positionSlider.addChangeListener(new ChangeListener() {
            private int currentHappening = 0;

            @Override
            public void stateChanged(ChangeEvent changeEvent) {
                final double progress = positionSlider.getValue() / slider_total_units;
                final double timeStamp = player.getDurationInSeconds() * progress;
                if (positionSlider.getValueIsAdjusting()) {
                    currentHappening = 1;
                    showTimestamp(timeStamp);
                } else {
                    if (currentHappening == 1) {
                        currentHappening = 0;
                        final PlayerState currentState = player.getState();
                        player.pauze(timeStamp);
                        if (currentState == PlayerState.PLAYING) {
                            player.play();
                        }
                    }
                }
            }
        });

        progressLabel = new JLabel();
        totalLabel = new JLabel();
        setProgressLabelText(0, 0);

        JPanel subPanel = new JPanel(new BorderLayout());
        subPanel.add(progressLabel, BorderLayout.WEST);
        subPanel.add(positionSlider, BorderLayout.CENTER);
        subPanel.add(totalLabel, BorderLayout.EAST);

        JPanel panel = new JPanel(new BorderLayout());
        JLabel label = new JLabel("Progress (in %°)");
        label.setToolTipText("Progress in promille.");
        panel.add(label, BorderLayout.NORTH);
        panel.add(subPanel, BorderLayout.CENTER);
        panel.setBorder(new TitledBorder("Progress control"));


        return panel;
    }


    private JComponent createTempoPanel() {
        tempoSlider = new JSlider(0, 300);
        tempoSlider.setValue(100);
        final JLabel label = new JLabel("Tempo: 100%");
        tempoSlider.setPaintLabels(true);
        tempoSlider.addChangeListener(arg0 -> {
            final double newTempo = tempoSlider.getValue() / 100.0;
            label.setText(String.format("Tempo: %3d", tempoSlider.getValue()) + "%");
            player.setTempo(newTempo);
        });

        JPanel panel = new JPanel(new BorderLayout());

        label.setToolTipText("The time stretching factor in % (100 is no change).");
        panel.add(label, BorderLayout.NORTH);
        panel.add(tempoSlider, BorderLayout.CENTER);
        panel.setBorder(new TitledBorder("Tempo control"));
        return panel;
    }

    private JComponent createButtonPanel() {
        JPanel fileChooserPanel = new JPanel(new GridLayout(1, 0));
        fileChooserPanel.setBorder(new TitledBorder("Actions"));

        fileChooser = new JFileChooser();

        final JButton chooseFileButton = new JButton("Open...");
        chooseFileButton.addActionListener(arg0 -> {
            int returnVal = fileChooser.showOpenDialog(AdvancedAudioPlayer.this);
            if (returnVal == JFileChooser.APPROVE_OPTION) {
                File file = fileChooser.getSelectedFile();
                PlayerState currentState = player.getState();
                player.load(file);
                if (currentState == PlayerState.NO_FILE_LOADED || currentState == PlayerState.PLAYING) {
                    player.play();
                }
            }  //canceled

        });
        fileChooserPanel.add(chooseFileButton);

        stopButton = new JButton("Stop");
        fileChooserPanel.add(stopButton);
        stopButton.addActionListener(arg0 -> player.stop());

        playButton = new JButton("Play");
        playButton.addActionListener(arg0 -> player.play());
        fileChooserPanel.add(playButton);

        pauzeButton = new JButton("Pauze");
        pauzeButton.addActionListener(arg0 -> player.pauze());
        fileChooserPanel.add(pauzeButton);

        return fileChooserPanel;
    }

    private void setProgressLabelText(final double current, final double max) {
        progressLabel.setText(formattedToString(current));
        totalLabel.setText(formattedToString(max));
    }

    private JComponent createGainPanel() {
        gainSlider = new JSlider(0, 200);
        gainSlider.setValue(100);
        gainSlider.setPaintLabels(true);
        gainSlider.setPaintTicks(true);
        final JLabel label = new JLabel("Gain: 100%");
        gainSlider.addChangeListener(arg0 -> {
            double gainValue = gainSlider.getValue() / 100.0;
            label.setText(String.format("Gain: %3d", gainSlider.getValue()) + "%");
            player.setGain(gainValue);
        });

        JPanel gainPanel = new JPanel(new BorderLayout());
        label.setToolTipText("Volume in % (100 is no change).");
        gainPanel.add(label, BorderLayout.NORTH);
        gainPanel.add(gainSlider, BorderLayout.CENTER);
        gainPanel.setBorder(new TitledBorder("Volume control"));
        return gainPanel;
    }

    AudioProcessor fftProcessor = new AudioProcessor() {

        FFT fft = null;
        int prevSize = 0;
        float[] amplitudes = null;

        @Override
        public void processingFinished() {
            // TODO Auto-generated method stub
        }

        @Override
        public boolean process(AudioEvent audioEvent) {
            final float[] audioFloatBuffer = audioEvent.getFloatBuffer();
            final int bufferSize = audioFloatBuffer.length;
            final float[] transformbuffer = new float[bufferSize * 2];
            if (prevSize != bufferSize) {
                fft = new FFT(bufferSize);
                amplitudes = new float[bufferSize / 2];
            }

            System.arraycopy(audioFloatBuffer, 0, transformbuffer, 0, bufferSize);
            fft.forwardTransform(transformbuffer);
            fft.modulus(transformbuffer, amplitudes);
            panel.drawFFT(0.0, amplitudes, fft);
            return true;
        }

    };


    public static void main(String... args) throws InterruptedException, InvocationTargetException {
        SwingUtilities.invokeAndWait(() -> {
            JFrame frame = new AdvancedAudioPlayer();
            frame.pack();
            frame.setSize(450, 650);
            frame.setVisible(true);
        });
    }
}
