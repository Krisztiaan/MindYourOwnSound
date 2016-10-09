package hu.krisztiaan.mobplayer;

import android.Manifest;
import android.animation.Animator;
import android.content.pm.PackageManager;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;

import com.choosemuse.libmuse.ConnectionState;
import com.choosemuse.libmuse.Eeg;
import com.choosemuse.libmuse.LibmuseVersion;
import com.choosemuse.libmuse.Muse;
import com.choosemuse.libmuse.MuseArtifactPacket;
import com.choosemuse.libmuse.MuseConnectionListener;
import com.choosemuse.libmuse.MuseConnectionPacket;
import com.choosemuse.libmuse.MuseDataListener;
import com.choosemuse.libmuse.MuseDataPacket;
import com.choosemuse.libmuse.MuseDataPacketType;
import com.choosemuse.libmuse.MuseListener;
import com.choosemuse.libmuse.MuseManagerAndroid;
import com.cleveroad.audiovisualization.AudioVisualization;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity implements MoodClassifier.MoodChangeListener {
    public static final int TEMP_BUFFER_MAX = 1;
    public static final int CROSSFADE_TIME = 8000;
    public static final float CROSSFADE_SPEED = 0.01f;
    private static final String TAG = "MainActivity";
    private final Handler handler = new Handler();
    private final List<String> messageStack = new ArrayList<>();
    public ConnectionState connectionState = ConnectionState.UNKNOWN;
    private MoodClassifier moodClassifier = new MoodClassifier(this);
    private Double alphaValue;
    private Double betaValue;
    private Double deltaValue;
    private Double gammaValue;
    private Double thetaValue;
    /**
     * The MuseManager is how you detect Muse headbands and receive notifications
     * when the list of available headbands changes.
     */
    private MuseManagerAndroid manager;
    /**
     * A Muse refers to a Muse headband.  Use this to connect/disconnect from the
     * headband, register listeners to receive EEG data and get headband
     * configuration and version information.
     */
    private Muse muse;
    /**
     * The ConnectionListener will be notified whenever there is a change in
     * the connection state of a headband, for example when the headband connects
     * or disconnects.
     * <p>
     * Note that ConnectionListener is an inner class at the bottom of this file
     * that extends MuseConnectionListener.
     */
    private ConnectionListener connectionListener;
    /**
     * The DataListener is how you will receive EEG (and other) data from the
     * headband.
     * <p>
     * Note that DataListener is an inner class at the bottom of this file
     * that extends MuseDataListener.
     */
    private DataListener dataListener;
    private boolean alphaStale;
    private boolean betaStale;
    private boolean deltaStale;
    private boolean gammaStale;
    //--------------------------------------
    // UI Specific methods
    private boolean thetaStale;
    /**
     * The runnable that is used to update the UI at 60Hz.
     * <p>
     * We update the UI from this Runnable instead of in packet handlers
     * because packets come in at high frequency -- 220Hz or more for raw EEG
     * -- and it only makes sense to update the UI at about 60fps. The update
     * functions do some string allocation, so this reduces our memory
     * footprint and makes GC pauses less frequent/noticeable.
     */
    private final Runnable tickUi = new Runnable() {
        @Override
        public void run() {
            if (alphaStale) {
                updateAlpha();
            }
            if (betaStale) {
                updateBeta();
            }
            if (deltaStale) {
                updateDelta();
            }
            if (gammaStale) {
                updateGamma();
            }
            if (thetaStale) {
                updateTheta();
            }
            handler.postDelayed(tickUi, 1000 / 60);
        }
    };
    private boolean accelStale;
    /**
     * In the UI, the list of Muses you can connect to is displayed in a Spinner object for this example.
     * This spinner adapter contains the MAC addresses of all of the headbands we have discovered.
     */
    private ArrayAdapter<String> spinnerAdapter;
    /**
     * It is possible to pause the data transmission from the headband.  This boolean tracks whether
     * or not the data transmission is enabled as we allow the user to pause transmission in the UI.
     */
    private boolean dataTransmission = true;
    private boolean isGood = true;
    private AudioVisualization audioVisualization;
    private List<MediaPlayer> mediaPlayers = new ArrayList<>();
    private int mainMediaPlayer;
    private Map<MuseDataPacketType, Double> lastValues = new HashMap<>();
    private Map<MuseDataPacketType, List<Double>> tempBuffer = new HashMap<>();
    private boolean isPlaying = false;

    private static String toS(Double d) {
        return d == null ? "0" : String.valueOf(d);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // We need to set the context on MuseManagerAndroid before we can do anything.
        // This must come before other LibMuse API calls as it also loads the library.
        manager = MuseManagerAndroid.getInstance();
        manager.setContext(this);

        Log.i(TAG, "LibMuse version=" + LibmuseVersion.instance().getString());

        WeakReference<MainActivity> weakActivity =
                new WeakReference<MainActivity>(this);
        // Register a listener to receive connection state changes.
        connectionListener = new ConnectionListener(weakActivity);
        // Register a listener to receive data from a Muse.
        dataListener = new DataListener(weakActivity);
        // Register a listener to receive notifications of what Muse headbands
        // we can connect to.
        manager.setMuseListener(new MuseL(weakActivity));

        // Muse 2016 (MU-02) headbands use Bluetooth Low Energy technology to
        // simplify the connection process.  This requires access to the COARSE_LOCATION
        // or FINE_LOCATION permissions.  Make sure we have these permissions before
        // proceeding.
        ensurePermissions();

        // Load and initialize our UI.
        initUI();

        // Start our asynchronous updates of the UI.
        handler.post(tickUi);
    }

    @Override
    protected void onResume() {
        super.onResume();
        searchDevices();
    }

    protected void onPause() {
        super.onPause();
        // It is important to call stopListening when the Activity is paused
        // to avoid a resource leak from the LibMuse library.
        manager.stopListening();
    }

    private void searchDevices() {
        // The user has pressed the "Refresh" button.
        // Start listening for nearby or paired Muse headbands. We call stopListening
        // first to make sure startListening will clear the list of headbands and start fresh.
        manager.stopListening();
        manager.startListening();
    }

    private void connect() {

        manager.stopListening();

        List<Muse> availableMuses = manager.getMuses();
        Spinner musesSpinner = (Spinner) findViewById(R.id.deviceSpinner);

        // Check that we actually have something to connect to.
        if (availableMuses.size() < 1 || musesSpinner.getAdapter().getCount() < 1) {
            Log.w(TAG, "There is nothing to connect to");
        } else {
            muse = availableMuses.get(musesSpinner.getSelectedItemPosition());
            muse.unregisterAllListeners();
            muse.registerConnectionListener(connectionListener);
            muse.registerDataListener(dataListener, MuseDataPacketType.ALPHA_ABSOLUTE);
            muse.registerDataListener(dataListener, MuseDataPacketType.BETA_ABSOLUTE);
            muse.registerDataListener(dataListener, MuseDataPacketType.DELTA_ABSOLUTE);
            muse.registerDataListener(dataListener, MuseDataPacketType.GAMMA_ABSOLUTE);
            muse.registerDataListener(dataListener, MuseDataPacketType.THETA_ABSOLUTE);
            muse.registerDataListener(dataListener, MuseDataPacketType.IS_GOOD);
            muse.registerDataListener(dataListener, MuseDataPacketType.BATTERY);
            muse.registerDataListener(dataListener, MuseDataPacketType.DRL_REF);
            muse.registerDataListener(dataListener, MuseDataPacketType.QUANTIZATION);

            // Initiate a connection to the headband and stream the data asynchronously.
            muse.runAsynchronously();
        }

    }

    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.imgPlay:
                play();
                v.setVisibility(View.GONE);
                break;
            case R.id.imgStop:
                stop();
                v.setVisibility(View.GONE);
                break;
        }
    }

    @Override
    public void onMoodChange(MoodClassifier.Mood nextMood) {
        if (isPlaying) {
            showInfo("Mood: " + nextMood.name());
            startNewMediaPlayer(MoodMusicProvider.getMediaPath(nextMood));
        }
    }

    public void play() {
        if (connectionState != ConnectionState.CONNECTED && connectionState != ConnectionState.CONNECTING) {
            connect();
            showInfo("Trying to connect...");
            return;
        }
        showInfo("Playing music...");
        isPlaying = true;
    }

    private void showInfo(final String info) {
        if (!messageStack.isEmpty() && !messageStack.contains(info)) {
            messageStack.add(info);
            return;
        }
        final TextView textView = ((TextView) findViewById(R.id.txtInfo));
        textView.animate().alpha(1).setDuration(500).setListener(new Animator.AnimatorListener() {
            @Override
            public void onAnimationStart(Animator animation) {

            }

            @Override
            public void onAnimationEnd(Animator animation) {
                textView.setText(info);
                textView.animate().alpha(0).setDuration(500).setListener(new Animator.AnimatorListener() {
                    @Override
                    public void onAnimationStart(Animator animation) {

                    }

                    @Override
                    public void onAnimationEnd(Animator animation) {
                        messageStack.remove(info);
                        if (!messageStack.isEmpty()) showInfo(messageStack.get(0));
                    }

                    @Override
                    public void onAnimationCancel(Animator animation) {

                    }

                    @Override
                    public void onAnimationRepeat(Animator animation) {

                    }
                }).start();
            }

            @Override
            public void onAnimationCancel(Animator animation) {

            }

            @Override
            public void onAnimationRepeat(Animator animation) {

            }
        }).start();
    }

    @Deprecated
    private void stub() {
        throw new RuntimeException("STUB!!!");
    }

    public void stop() {
        showInfo("Music is stopped");
        for (MediaPlayer mp :
                mediaPlayers) {
            stopMediaPlayer(mp);
        }
        findViewById(R.id.imgStop).setVisibility(View.GONE);
        findViewById(R.id.imgPlay).setVisibility(View.VISIBLE);
    }

    private void setIsPlayable(boolean isPlayable) {
        findViewById(R.id.imgStop).setVisibility(isPlayable ? View.GONE : View.VISIBLE);
        findViewById(R.id.imgPlay).setVisibility(isPlayable ? View.VISIBLE : View.GONE);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (muse != null) {
            muse.disconnect(false);
        }
        stop();
    }

    private void ensurePermissions() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN) != PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(this, Manifest.permission.CAPTURE_AUDIO_OUTPUT) != PackageManager.PERMISSION_GRANTED)
            ActivityCompat.requestPermissions(MainActivity.this,
                    new String[]{Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.BLUETOOTH_ADMIN, Manifest.permission.CAPTURE_AUDIO_OUTPUT},
                    0);
    }

    public void museListChanged() {
        final List<Muse> list = manager.getMuses();
        spinnerAdapter.clear();
        for (Muse m : list) {
            spinnerAdapter.add(m.getName());
        }
    }

    public void receiveMuseConnectionPacket(final MuseConnectionPacket p, final Muse muse) {

        connectionState = p.getCurrentConnectionState();

        // Format a message to show the change of connection state in the UI.
        final String status = p.getPreviousConnectionState() + " -> " + connectionState;
        Log.i(TAG, status);

        // Update the UI with the change in connection state.
        handler.post(new Runnable() {
            @Override
            public void run() {
                stop();
                showInfo(status);
            }
        });

        if (connectionState == ConnectionState.DISCONNECTED) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    findViewById(R.id.deviceSpinner).setVisibility(View.VISIBLE);
                    setIsPlayable(true);
                }
            });
            this.muse = null;
        }

        if (connectionState == ConnectionState.CONNECTED) {
            findViewById(R.id.deviceSpinner).setVisibility(View.GONE);
            setIsPlayable(true);
        }
    }

    public void receiveMuseDataPacket(final MuseDataPacket p) {
        try {
            switch (p.packetType()) {
                case ALPHA_ABSOLUTE:
                    if (isGood)
                        alphaValue = getChannelValue(p);
                    collectSignal(alphaValue, p.packetType());
                    alphaStale = true;
                    break;
                case BETA_ABSOLUTE:
                    if (isGood)
                        betaValue = getChannelValue(p);
                    collectSignal(betaValue, p.packetType());
                    betaStale = true;
                    break;
                case DELTA_ABSOLUTE:
                    if (isGood)
                        deltaValue = getChannelValue(p);
                    collectSignal(deltaValue, p.packetType());
                    deltaStale = true;
                    break;
                case GAMMA_ABSOLUTE:
                    if (isGood)
                        gammaValue = getChannelValue(p);
                    collectSignal(gammaValue, p.packetType());
                    gammaStale = true;
                    break;
                case THETA_ABSOLUTE:
                    if (isGood)
                        thetaValue = getChannelValue(p);
                    collectSignal(thetaValue, p.packetType());
                    thetaStale = true;
                    break;
                case IS_GOOD:
                    isGood = (p.values().get(0) > .5);
                    break;
                case BATTERY:
                    for (double value :
                            p.values()) {
                        Log.i(TAG, "receiveMuseDataPacket BATTERY: " + value);
                    }
                    break;
                default:
                    break;
            }
        } catch (Exception e) {
            e.printStackTrace();
            throw e;
        }
    }

    @Nullable
    private Double getChannelValue(MuseDataPacket p) {
        return (p.getEegChannelValue(Eeg.EEG1) + p.getEegChannelValue(Eeg.EEG2) + p.getEegChannelValue(Eeg.EEG3) + p.getEegChannelValue(Eeg.EEG4)) / 4;
    }

    public void collectSignal(Double buffer, MuseDataPacketType type) {
        List<Double> bufferList = tempBuffer.get(type);
        if (bufferList == null) {
            tempBuffer.put(type, new ArrayList<Double>());
            bufferList = tempBuffer.get(type);
        }
        int size = bufferList.size();
        if (size > TEMP_BUFFER_MAX) {
            Double avg = 0d;
            for (Double currentVal :
                    bufferList) {
                avg += currentVal;
            }
            bufferList.clear();
            avg /= 4;
            lastValues.put(type, avg);

            if (lastValues.containsKey(MuseDataPacketType.ALPHA_ABSOLUTE) &&
                    lastValues.containsKey(MuseDataPacketType.BETA_ABSOLUTE) &&
                    lastValues.containsKey(MuseDataPacketType.GAMMA_ABSOLUTE) &&
                    lastValues.containsKey(MuseDataPacketType.THETA_ABSOLUTE) &&
                    lastValues.containsKey(MuseDataPacketType.DELTA_ABSOLUTE)
                    ) {
                moodClassifier.setWaves(lastValues.get(MuseDataPacketType.ALPHA_ABSOLUTE),
                        lastValues.get(MuseDataPacketType.BETA_ABSOLUTE),
                        lastValues.get(MuseDataPacketType.DELTA_ABSOLUTE),
                        lastValues.get(MuseDataPacketType.GAMMA_ABSOLUTE),
                        lastValues.get(MuseDataPacketType.THETA_ABSOLUTE)
                );
                lastValues.clear();
            }
        }
        bufferList.add(buffer);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
//        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN) != PackageManager.PERMISSION_GRANTED ||
//                ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
//                ActivityCompat.checkSelfPermission(this, Manifest.permission.CAPTURE_AUDIO_OUTPUT) != PackageManager.PERMISSION_GRANTED) {
////            ensurePermissions();
//        }
        searchDevices();
    }

    private void stopMediaPlayer(final MediaPlayer mediaPlayer) {
        stopMediaPlayer(mediaPlayer, false);
    }

    private void stopMediaPlayer(final MediaPlayer mediaPlayer, boolean isRemoved) {
        if (!isRemoved) mediaPlayers.remove(mediaPlayer);
        new Thread(new Runnable() {
            @Override
            public void run() {
                float volume = 1f;
                while (volume > 0) {
                    if (volume < 0) volume = 0;
                    mediaPlayer.setVolume(volume, volume);
                    volume -= CROSSFADE_SPEED;
                    try {
                        Thread.sleep((long) (CROSSFADE_TIME * CROSSFADE_SPEED));
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                        break;
                    }
                }
                mediaPlayer.stop();
                mediaPlayer.release();
            }
        }).start();
    }

    private void startNewMediaPlayer(int audioPath) {
        for (MediaPlayer mp :
                mediaPlayers) {
            stopMediaPlayer(mp, true);
        }
        mediaPlayers.clear();
        final MediaPlayer newMediaPlayer = MediaPlayer.create(this, audioPath);
        mediaPlayers.add(newMediaPlayer);
        newMediaPlayer.setLooping(true);
        newMediaPlayer.setVolume(0, 0);
        newMediaPlayer.start();

//        VisualizerDbmHandler vizualizerHandler = DbmHandler.Factory.newVisualizerHandler(this, newMediaPlayer);
//        audioVisualization.linkTo(vizualizerHandler);

        new Thread(new Runnable() {
            @Override
            public void run() {
                float volume = 0f + CROSSFADE_SPEED;
                while (volume < 1) {
                    if (volume > 1) volume = 1;
                    newMediaPlayer.setVolume(volume, volume);
                    volume += CROSSFADE_SPEED;
                    try {
                        Thread.sleep((long) (CROSSFADE_TIME * CROSSFADE_SPEED));
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                        break;
                    }
                    try {
                        if (mediaPlayers.get(0) != newMediaPlayer) {
                            stopMediaPlayer(newMediaPlayer);
                            break;
                        }
                    } catch (IndexOutOfBoundsException ioobe) {

                    }
                }
            }
        }).start();
    }

    private void initUI() {
        setContentView(R.layout.activity_main);

        audioVisualization = (AudioVisualization) findViewById(R.id.visualizer);

        spinnerAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item);
        Spinner musesSpinner = (Spinner) findViewById(R.id.deviceSpinner);
        musesSpinner.setAdapter(spinnerAdapter);
    }

    private void updateAlpha() {
        ((BubbleView) findViewById(R.id.alphaBubble)).setSize(alphaValue.floatValue());
    }

    private void updateBeta() {
        ((BubbleView) findViewById(R.id.betaBubble)).setSize(betaValue.floatValue());
    }

    private void updateDelta() {
        ((BubbleView) findViewById(R.id.deltaBubble)).setSize(deltaValue.floatValue());
    }

    private void updateGamma() {
        ((BubbleView) findViewById(R.id.gammaBubble)).setSize(gammaValue.floatValue());
    }

    private void updateTheta() {
        ((BubbleView) findViewById(R.id.thetaBubble)).setSize(thetaValue.floatValue());
    }

    class MuseL extends MuseListener {
        final WeakReference<MainActivity> activityRef;

        MuseL(final WeakReference<MainActivity> activityRef) {
            this.activityRef = activityRef;
        }

        @Override
        public void museListChanged() {
            activityRef.get().museListChanged();
        }
    }

    class ConnectionListener extends MuseConnectionListener {
        final WeakReference<MainActivity> activityRef;

        ConnectionListener(final WeakReference<MainActivity> activityRef) {
            this.activityRef = activityRef;
        }

        @Override
        public void receiveMuseConnectionPacket(final MuseConnectionPacket p, final Muse muse) {
            activityRef.get().receiveMuseConnectionPacket(p, muse);
        }
    }

    class DataListener extends MuseDataListener {
        final WeakReference<MainActivity> activityRef;

        DataListener(final WeakReference<MainActivity> activityRef) {
            this.activityRef = activityRef;
        }

        @Override
        public void receiveMuseDataPacket(final MuseDataPacket p, final Muse muse) {
            activityRef.get().receiveMuseDataPacket(p);
        }

        @Override
        public void receiveMuseArtifactPacket(final MuseArtifactPacket p, final Muse muse) {

        }
    }

}
