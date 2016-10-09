package hu.krisztiaan.mobplayer;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * Created by krisz on 10/9/2016.
 */

public class MoodMusicProvider {
    private static final Map<MoodClassifier.Mood, Integer[]> MOOD_MAP = new HashMap<MoodClassifier.Mood, Integer[]>() {{
        put(MoodClassifier.Mood.RELAX, new Integer[]{R.raw.relaxed1});
        put(MoodClassifier.Mood.CONSCIOUS, new Integer[]{R.raw.conscious1, R.raw.conscious2});
        put(MoodClassifier.Mood.MEDITATE, new Integer[]{R.raw.meditate1});
        put(MoodClassifier.Mood.DEEP, new Integer[]{R.raw.deep1, R.raw.deep2});
        put(MoodClassifier.Mood.INTENSE, new Integer[]{R.raw.intense1, R.raw.intense2});
    }};

    public static int getMediaPath(MoodClassifier.Mood mood) {
        Random random = new Random();
        return MOOD_MAP.get(mood)[random.nextInt(MOOD_MAP.get(mood).length)];
    }
}
