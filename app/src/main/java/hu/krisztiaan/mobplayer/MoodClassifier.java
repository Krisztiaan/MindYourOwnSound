package hu.krisztiaan.mobplayer;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Pair;

import java.util.HashMap;
import java.util.Map;

/**
 * Created by krisz on 10/9/2016.
 */

public class MoodClassifier {
    public static final int MOOD_DEFAULT_STRENGTH = 50;
    public enum Mood {
        RELAX, //alpha
        CONSCIOUS, //beta
        MEDITATE, //theta
        DEEP, //delta
        INTENSE //gamma
    }

    private static final Map<Wave, Pair<Double, Double>> ranges = new HashMap<Wave, Pair<Double, Double>>() {{
        put(Wave.ALPHA, Pair.create(0d, 1d));
        put(Wave.BETA, Pair.create(0d, 1d));
        put(Wave.DELTA, Pair.create(0d, 1d));
        put(Wave.GAMMA, Pair.create(0d, 1d));
        put(Wave.THETA, Pair.create(0d, .5d));
    }};

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

    private double getCorrectedValue(Wave type, double value) {
        Pair<Double, Double> range = ranges.get(type);
        double fullRange = range.second-range.first;
        double relValue = value-range.first;
        return relValue/fullRange;
    }

    private final @NonNull MoodChangeListener moodChangeListener;

    public MoodClassifier(@NonNull MoodChangeListener listener) {
        moodChangeListener = listener;
    }

    public void setWaves(double alpha, double beta, double delta, double gamma, double theta) {

        Mood nextMood = getMoodForValues(getCorrectedValue(Wave.ALPHA, alpha),
                getCorrectedValue(Wave.BETA, beta),
                        getCorrectedValue(Wave.DELTA, delta),
                getCorrectedValue(Wave.GAMMA, gamma),
                getCorrectedValue(Wave.THETA, theta));

        if(nextMood==null) return;

        if(currentMood!=nextMood) {
            currentMoodStrength--;
            if(currentMoodStrength<=0) {
                currentMood = nextMood;
                moodChangeListener.onMoodChange(currentMood);
                currentMoodStrength = MOOD_DEFAULT_STRENGTH;
            }
        }
    }

    private double getMax(Double... values) {
        double retVal = 0;
        for (Double val :
                values) {
            if(val>retVal)
                retVal = val;
        }
        return retVal;
    }

    private Mood currentMood = Mood.CONSCIOUS;
    private int currentMoodStrength = MOOD_DEFAULT_STRENGTH;

    @Nullable
    private Mood getMoodForValues(double alpha, double beta, double delta, double gamma, double theta) {
        double max = (getMax(alpha, beta, delta, gamma, theta));
        if(max==alpha) return Mood.RELAX;
        if(max==beta) return Mood.CONSCIOUS;
        if(max==delta) return Mood.MEDITATE;
        if(max==gamma) return Mood.DEEP;
        if(max==theta) return Mood.INTENSE;
        else return null;
    }
}
