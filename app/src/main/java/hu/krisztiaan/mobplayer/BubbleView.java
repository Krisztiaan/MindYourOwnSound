package hu.krisztiaan.mobplayer;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.res.TypedArray;
import android.os.Build;
import android.support.annotation.Nullable;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * Created by krisz on 10/9/2016.
 */

public class BubbleView extends FrameLayout {
    public BubbleView(Context context) {
        super(context);
        initView(context, null);
    }

    public BubbleView(Context context, AttributeSet attrs) {
        super(context, attrs);
        initView(context,attrs);
    }

    public BubbleView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        initView(context,attrs);
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    public BubbleView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        initView(context,attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

    }

    LayoutInflater inflater;

    TextView txt;
    FrameLayout bubble;

    public void setSize(float size) {
        this.animate().scaleX(size).scaleY(size).start();
    }

    private void initView(Context context, @Nullable AttributeSet attrs){
        inflater = (LayoutInflater)context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        bubble = (FrameLayout) inflater.inflate(R.layout.view_bubble_text, null);
        txt = (TextView) bubble.findViewById(R.id.txt);
        this.addView(bubble);

        if (attrs != null) {
        TypedArray ta = context.obtainStyledAttributes(attrs, R.styleable.BubbleView, 0, 0);
        try {
            int color = ta.getColor(R.styleable.BubbleView_bv_color, -1);
            if(color != -1)
            bubble.setBackgroundColor(color);
            txt.setText(ta.getString(R.styleable.BubbleView_bv_text));
        } finally {
            ta.recycle();
        }
        }
    }
}
