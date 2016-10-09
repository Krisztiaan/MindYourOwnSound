package hu.krisztiaan.mobplayer;

import android.Manifest;
import android.animation.Animator;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.balysv.materialripple.MaterialRippleLayout;
import com.choosemuse.libmuse.ConnectionState;
import com.choosemuse.libmuse.Eeg;
import com.choosemuse.libmuse.Muse;
import com.choosemuse.libmuse.MuseArtifactPacket;
import com.choosemuse.libmuse.MuseConnectionListener;
import com.choosemuse.libmuse.MuseConnectionPacket;
import com.choosemuse.libmuse.MuseDataListener;
import com.choosemuse.libmuse.MuseDataPacket;
import com.choosemuse.libmuse.MuseDataPacketType;
import com.choosemuse.libmuse.MuseListener;
import com.choosemuse.libmuse.MuseManagerAndroid;
import com.wang.avi.AVLoadingIndicatorView;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity implements MoodClassifier.MoodChangeListener {
    public static final int TEMP_BUFFER_MAX = 1;
    public static final int CROSSFADE_TIME = 8000;
    public static final float CROSSFADE_SPEED = 0.01f;
    public static final int RECONNECT_PROMPT = 3;
    private static final String TAG = "MainActivity";
    private final Handler handler = new Handler();
    private final List<String> displayInfoStack = new ArrayList<>();
    public ConnectionState connectionState = ConnectionState.UNKNOWN;
    TextView txtStatusInfo;
    ImageView imgDisplayGrad;
    TextView txtInteractionInfo;
    Handler connHandler;
    private MoodClassifier moodClassifier = new MoodClassifier(this);
    private Double alphaValue = 0d;
    private Double betaValue = 0d;
    private Double deltaValue = 0d;
    private Double gammaValue = 0d;
    private Double thetaValue = 0d;
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
            float v = Double.valueOf(deltaValue + thetaValue).floatValue();
            v /= 2;
            if (v > 1) v = 1f;
            if (v < 0) v = 0f;
            animateBrainChange(v);
            handler.postDelayed(tickUi, 1000 / 60);
        }
    };
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
    private DataListener dataListener;
    private boolean isGood = true;
    private List<MediaPlayer> mediaPlayers = new ArrayList<>();
    private Map<MuseDataPacketType, Double> lastValues = new HashMap<>();
    private Map<MuseDataPacketType, List<Double>> tempBuffer = new HashMap<>();
    private AVLoadingIndicatorView loadingIndicatorView;
    private boolean isPlaying = false;
    private int reconnectCount = 0;
    private String previousInterInfo = "";
    private final Runnable connectRunnable = new Runnable() {
        @Override
        public void run() {
            connect();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        manager = MuseManagerAndroid.getInstance();
        manager.setContext(this);
        WeakReference<MainActivity> weakActivity =
                new WeakReference<MainActivity>(this);
        connectionListener = new ConnectionListener(weakActivity);
        dataListener = new DataListener(weakActivity);
        manager.setMuseListener(new MuseL());
        ensurePermissions();
        initUI();
        handler.post(tickUi);
    }

    @Override
    protected void onResume() {
        super.onResume();
        searchDevices();
    }

    protected void onPause() {
        super.onPause();
        manager.stopListening();
    }

    private void searchDevices() {
        manager.stopListening();
        manager.startListening();
    }

    private void connect() {

        manager.stopListening();

        List<Muse> availableMuses = manager.getMuses();
//        Spinner musesSpinner = (Spinner) findViewById(R.id.deviceSpinner);

        // Check that we actually have something to connect to.
        if (availableMuses.size() < 1) {
            showInteractionInfo("- pair and turn on the muse -");
        } else {
            muse = availableMuses.get(0);
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

    @Override
    public void onMoodChange(MoodClassifier.Mood nextMood) {
        if (isPlaying) {
            showStatusInfo("Now playing: " + nextMood.toString());
            startNewMediaPlayer(MoodMusicProvider.getMediaPath(nextMood));
        }
    }

    public void play() {
        if (connectionState != ConnectionState.CONNECTED && connectionState != ConnectionState.CONNECTING) {
            connect();
            showStatusInfo("Trying to connect...");
            return;
        }
        showStatusInfo("Playing music...");
        showInteractionInfo("- tap to stop -");
        imgDisplayGrad.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stop();
            }
        });
        isPlaying = true;
        onMoodChange(moodClassifier.getMood());
    }

    private void animateBrainChange(float value) {
        imgDisplayGrad.animate().alpha(value).setDuration(100).start();
    }

    private void showStatusInfo(final String info) {
        if (!displayInfoStack.isEmpty() && !displayInfoStack.contains(info)) {
            displayInfoStack.add(info);
            return;
        }
        txtStatusInfo.animate().alpha(0).setDuration(500).setListener(new Animator.AnimatorListener() {
            @Override
            public void onAnimationStart(Animator animation) {

            }

            @Override
            public void onAnimationEnd(Animator animation) {
                txtStatusInfo.setText(info);
                txtStatusInfo.animate().alpha(1).setDuration(500).setListener(new Animator.AnimatorListener() {
                    @Override
                    public void onAnimationStart(Animator animation) {

                    }

                    @Override
                    public void onAnimationEnd(Animator animation) {
                        displayInfoStack.remove(info);
                        if (!displayInfoStack.isEmpty()) showStatusInfo(displayInfoStack.get(0));
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

    public void stop() {
        isPlaying = false;
        if (mediaPlayers.size() <= 0) showStatusInfo("music is stopped");
        for (MediaPlayer mp :
                mediaPlayers) {
            stopMediaPlayer(mp);
        }
        imgDisplayGrad.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                play();
            }
        });
        showStatusInfo("Ready");
        showInteractionInfo("- tap to start music -");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (muse != null) {
            muse.disconnect(false);
        }
        stop();
    }

    private void checkPermission(String permission, int checkId) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(MainActivity.this, new String[]{permission},
                    checkId);

        }
    }

    private void ensurePermissions() {
        checkPermission(Manifest.permission.BLUETOOTH_ADMIN, 10);
        checkPermission(Manifest.permission.CAPTURE_AUDIO_OUTPUT, 11);
        checkPermission(Manifest.permission.ACCESS_COARSE_LOCATION, 12);
    }

    public void receiveMuseConnectionPacket(final MuseConnectionPacket p, final Muse muse) {

        connectionState = p.getCurrentConnectionState();

        // Format a message to show the change of connection state in the UI.
        final String status = p.getPreviousConnectionState() + " -> " + connectionState;
        Log.i(TAG, status);

        if (connectionState == ConnectionState.DISCONNECTED) {
            if (connHandler != null) {
                connHandler.removeCallbacks(connectRunnable);
            }
            connHandler = new Handler();
            connHandler.postDelayed(connectRunnable, 1000);
            connect();
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    reconnectCount++;
                    showStatusInfo("Connecting, please wait...");
                    if (reconnectCount > RECONNECT_PROMPT) {
                        showInteractionInfo("- try to restart the device -");
                    }

                }
            });
            this.muse = null;
        }

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (connectionState == ConnectionState.CONNECTED) {
                    reconnectCount = 0;
                    setIsLoading(false);
                    showStatusInfo("Connected");
                    stop();
                } else {
                    setIsLoading(true);
                }
            }
        });
    }

    private void setIsLoading(boolean isLoading) {
        if (isLoading) {
            loadingIndicatorView.smoothToShow();
        } else {
            loadingIndicatorView.smoothToHide();
        }
    }

    public void receiveMuseDataPacket(final MuseDataPacket p) {
        try {
            switch (p.packetType()) {
                case ALPHA_ABSOLUTE:
                    if (isGood)
                        alphaValue = getChannelValue(p);
                    collectSignal(alphaValue, p.packetType());
                    break;
                case BETA_ABSOLUTE:
                    if (isGood)
                        betaValue = getChannelValue(p);
                    collectSignal(betaValue, p.packetType());
                    break;
                case DELTA_ABSOLUTE:
                    if (isGood)
                        deltaValue = getChannelValue(p);
                    collectSignal(deltaValue, p.packetType());
                    break;
                case GAMMA_ABSOLUTE:
                    if (isGood)
                        gammaValue = getChannelValue(p);
                    collectSignal(gammaValue, p.packetType());
                    break;
                case THETA_ABSOLUTE:
                    if (isGood)
                        thetaValue = getChannelValue(p);
                    collectSignal(thetaValue, p.packetType());
                    break;
                case IS_GOOD:
                    boolean newIsGood = (p.values().get(0) > .5);
                    if (newIsGood != isGood) {
                        isGood = newIsGood;
                        if (!isGood) {
                            previousInterInfo = txtInteractionInfo.getText().toString();
                            showInteractionInfo("- please adjust the device -");
                        } else {
                            showInteractionInfo(previousInterInfo);
                        }
                    }
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

    public void showInteractionInfo(String interactionInfo) {
        if (txtInteractionInfo.getText().equals("- please adjust the device -")) {
            previousInterInfo = interactionInfo;
        }
        txtInteractionInfo.setText(interactionInfo);
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
        try {
            stopMediaPlayer(mediaPlayer, false);
        } catch (IllegalStateException ise) {
            showStatusInfo("Illegal state reached, please restart");
        }
    }

    private void stopMediaPlayer(final MediaPlayer mediaPlayer, boolean isRemoved) {
        if (!isRemoved) {
            if(!mediaPlayers.remove(mediaPlayer))
            return;
        }
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

        loadingIndicatorView = (AVLoadingIndicatorView) findViewById(R.id.avi);
        txtStatusInfo = (TextView) findViewById(R.id.txtStatusInfo);
        txtInteractionInfo = (TextView) findViewById(R.id.txtInteractionInfo);
        imgDisplayGrad = (ImageView) findViewById(R.id.imgDisplayGrad);

        MaterialRippleLayout.on(imgDisplayGrad)
                .rippleColor(Color.LTGRAY)
                .rippleDuration(100)
                .create();
        loadingIndicatorView.hide();
        imgDisplayGrad.animate().alpha(0).setDuration(200).start();
    }

    class MuseL extends MuseListener {

        MuseL() {

        }

        @Override
        public void museListChanged() {
            if (connectionState != ConnectionState.CONNECTED)
                connect();
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
