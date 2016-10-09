package hu.krisztiaan.mobplayer;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;
import android.util.Pair;

import java.util.HashMap;
import java.util.Map;

import static android.content.ContentValues.TAG;

/**
 * Created by krisz on 10/9/2016.
 */

public class MoodClassifier {
    public static final int MOOD_STRENGTH_SWITCH_TRESHOLD = 3;
    public static final int MOOD_STRENGTH_RESET_VOLUME = 30;
    private static final Map<Wave, Pair<Double, Double>> ranges = new HashMap<Wave, Pair<Double, Double>>() {{
        put(Wave.ALPHA, Pair.create(0d, 1d));
        put(Wave.BETA, Pair.create(0d, 1d));
        put(Wave.DELTA, Pair.create(0d, 1d));
        put(Wave.GAMMA, Pair.create(0d, 1d));
        put(Wave.THETA, Pair.create(0d, .5d));
    }};
    @NonNull
    private final MoodChangeListener moodChangeListener;
    private Map<Mood, Integer> moodStrength = new HashMap<>();
    private Mood currentMood = Mood.CONSCIOUS;

    public MoodClassifier(@NonNull MoodChangeListener listener) {
        moodChangeListener = listener;
        initMoodStrength();
    }

    private double getValueNormalized(Wave type, double value) {
        Pair<Double, Double> range = ranges.get(type);
        double fullRange = range.second - range.first;
        double relValue = value - range.first;
        return relValue / fullRange;
    }

    public void setWaves(double alpha, double beta, double delta, double gamma, double theta) {

        Mood nextMood = getMoodForValues(getValueNormalized(Wave.ALPHA, alpha),
                getValueNormalized(Wave.BETA, beta),
                getValueNormalized(Wave.DELTA, delta),
                getValueNormalized(Wave.GAMMA, gamma),
                getValueNormalized(Wave.THETA, theta));

        if (nextMood == null) return;

        moodStrength.put(nextMood, moodStrength.get(nextMood) + 1);

        checkMoodRace();
    }

    private void switchMood(Mood mood) {
        currentMood = mood;
        moodChangeListener.onMoodChange(currentMood);
        initMoodStrength();
    }

    private void checkMoodRace() {
        Mood strongest = currentMood;
        int currentStrength = moodStrength.get(currentMood);
        int strongestStrength = currentStrength;
        for (Mood mood :
                moodStrength.keySet()) {
            int moodStrengthValue = moodStrength.get(mood);
            Log.i(TAG, "checkMoodRace " + mood.name() + ":" + moodStrengthValue);
            if (strongestStrength < moodStrengthValue) {
                strongest = mood;
                strongestStrength = moodStrength.get(mood);
            }
        }
        if (strongestStrength - MOOD_STRENGTH_SWITCH_TRESHOLD > currentStrength)
            switchMood(strongest);
        else if (strongestStrength > MOOD_STRENGTH_RESET_VOLUME) initMoodStrength();
    }

    private void initMoodStrength() {
        moodStrength.put(Mood.RELAX, 0);
        moodStrength.put(Mood.CONSCIOUS, 0);
        moodStrength.put(Mood.MEDITATE, 0);
        moodStrength.put(Mood.DEEP, 0);
        moodStrength.put(Mood.INTENSE, 0);
    }

    private double getMax(Double... values) {
        double retVal = 0;
        for (Double val :
                values) {
            if (val > retVal)
                retVal = val;
        }
        return retVal;
    }

    @Nullable
    private Mood getMoodForValues(double alpha, double beta, double delta, double gamma, double theta) {
        double max = (getMax(alpha, beta, delta, gamma, theta));
        if (max == alpha) return Mood.RELAX;
        if (max == beta) return Mood.CONSCIOUS;
        if (max == delta) return Mood.MEDITATE;
        if (max == gamma) return Mood.DEEP;
        if (max == theta) return Mood.INTENSE;
        else return null;
    }

    public Mood getMood() {
        return currentMood;
    }

    public enum Mood {
        RELAX, //alpha
        CONSCIOUS, //beta
        MEDITATE, //theta
        DEEP, //delta
        INTENSE; //gamma

        @Override
        public String toString() {
            switch (this) {
                case RELAX:
                    return "Relaxing";

                case CONSCIOUS:
                    return "Conscious";

                case MEDITATE:
                    return "Meditative";

                case DEEP:
                    return "Deep";

                case INTENSE:
                    return "Intense";

                default:
                    return "Unknown";
            }
        }
    }

    public enum Wave {
        ALPHA,
        BETA,
        DELTA,
        GAMMA,
        THETA
    }

    public interface MoodChangeListener {
        void onMoodChange(Mood nextMood);
    }
}
