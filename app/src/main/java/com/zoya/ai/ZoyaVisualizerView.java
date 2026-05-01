package com.zoya.ai;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.core.content.res.ResourcesCompat;

/**
 * Animated circular visualizer with 5 concentric rotating rings and a glowing center core.
 * Colors and rotation speeds change based on the current app state.
 */
public class ZoyaVisualizerView extends View {

    public enum State {
        IDLE, LISTENING, PROCESSING, SPEAKING
    }

    private State currentState = State.IDLE;

    // Paints
    private final Paint ring1Paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring2Paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring3Paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring4Paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring5Paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint corePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint coreBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // Rotation angles
    private float angle1 = 0, angle2 = 0, angle3 = 0, angle4 = 0, angle5 = 0;

    // Ring radii (fractions of view size)
    private static final float RING1_RADIUS = 0.42f;
    private static final float RING2_RADIUS = 0.36f;
    private static final float RING3_RADIUS = 0.30f;
    private static final float RING4_RADIUS = 0.24f;
    private static final float RING5_RADIUS = 0.18f;
    private static final float CORE_RADIUS = 0.12f;
    private static final float GLOW_RADIUS = 0.50f;

    // State colors
    private int stateColor;
    private int stateColorAlpha;

    // Speed multipliers per state
    private float speedMultiplier = 1f;

    // Pulse animation
    private float pulseScale = 1f;
    private ValueAnimator pulseAnimator;

    // Main animation
    private ValueAnimator rotationAnimator;

    private Typeface rajdhaniFont;

    public ZoyaVisualizerView(Context context) {
        super(context);
        init();
    }

    public ZoyaVisualizerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public ZoyaVisualizerView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setLayerType(LAYER_TYPE_SOFTWARE, null);

        try {
            rajdhaniFont = ResourcesCompat.getFont(getContext(), R.font.rajdhani_bold);
        } catch (Exception e) {
            rajdhaniFont = Typeface.DEFAULT_BOLD;
        }

        setupPaints();
        setState(State.IDLE);
        startAnimations();
    }

    private void setupPaints() {
        // All rings are stroked
        ring1Paint.setStyle(Paint.Style.STROKE);
        ring1Paint.setStrokeWidth(2f);

        ring2Paint.setStyle(Paint.Style.STROKE);
        ring2Paint.setStrokeWidth(1.5f);

        ring3Paint.setStyle(Paint.Style.STROKE);
        ring3Paint.setStrokeWidth(2.5f);

        ring4Paint.setStyle(Paint.Style.STROKE);
        ring4Paint.setStrokeWidth(1.5f);

        ring5Paint.setStyle(Paint.Style.STROKE);
        ring5Paint.setStrokeWidth(3f);

        // Core
        corePaint.setStyle(Paint.Style.FILL);
        coreBorderPaint.setStyle(Paint.Style.STROKE);
        coreBorderPaint.setStrokeWidth(2.5f);

        // Glow
        glowPaint.setStyle(Paint.Style.FILL);

        // Text
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(rajdhaniFont);
        textPaint.setLetterSpacing(0.3f);
    }

    public void setState(State state) {
        this.currentState = state;

        switch (state) {
            case IDLE:
                stateColor = 0xFF06B6D4; // Cyan
                speedMultiplier = 0.3f;
                break;
            case LISTENING:
                stateColor = 0xFF8B5CF6; // Violet
                speedMultiplier = 1.5f;
                break;
            case PROCESSING:
                stateColor = 0xFF38BDF8; // Sky Blue
                speedMultiplier = 2.5f;
                break;
            case SPEAKING:
                stateColor = 0xFFEC4899; // Pink
                speedMultiplier = 1.0f;
                break;
        }

        stateColorAlpha = Color.argb(60, Color.red(stateColor), Color.green(stateColor), Color.blue(stateColor));

        updatePaintColors();
        updatePulseAnimation();
    }

    private void updatePaintColors() {
        int alpha80 = Color.argb(80, Color.red(stateColor), Color.green(stateColor), Color.blue(stateColor));
        int alpha60 = Color.argb(60, Color.red(stateColor), Color.green(stateColor), Color.blue(stateColor));
        int alpha50 = Color.argb(50, Color.red(stateColor), Color.green(stateColor), Color.blue(stateColor));
        int alpha40 = Color.argb(40, Color.red(stateColor), Color.green(stateColor), Color.blue(stateColor));
        int alpha30 = Color.argb(30, Color.red(stateColor), Color.green(stateColor), Color.blue(stateColor));
        int alpha20 = Color.argb(20, Color.red(stateColor), Color.green(stateColor), Color.blue(stateColor));

        ring1Paint.setColor(alpha40);
        ring1Paint.setPathEffect(new DashPathEffect(new float[]{20, 10}, 0));

        ring2Paint.setColor(alpha50);
        ring2Paint.setPathEffect(new DashPathEffect(new float[]{4, 6}, 0));

        ring3Paint.setColor(alpha60);
        ring3Paint.setPathEffect(null);

        ring4Paint.setColor(alpha60);
        ring4Paint.setPathEffect(new DashPathEffect(new float[]{12, 8}, 0));

        ring5Paint.setColor(alpha80);
        ring5Paint.setPathEffect(new DashPathEffect(new float[]{6, 4}, 0));

        corePaint.setColor(Color.argb(40, Color.red(stateColor), Color.green(stateColor), Color.blue(stateColor)));
        coreBorderPaint.setColor(stateColor);
        coreBorderPaint.setMaskFilter(new BlurMaskFilter(8, BlurMaskFilter.Blur.NORMAL));

        glowPaint.setColor(alpha20);
        glowPaint.setMaskFilter(new BlurMaskFilter(100, BlurMaskFilter.Blur.NORMAL));

        textPaint.setColor(stateColor);
    }

    private void startAnimations() {
        rotationAnimator = ValueAnimator.ofFloat(0, 360);
        rotationAnimator.setDuration(10000);
        rotationAnimator.setRepeatCount(ValueAnimator.INFINITE);
        rotationAnimator.setInterpolator(new LinearInterpolator());
        rotationAnimator.addUpdateListener(animation -> {
            float base = (float) animation.getAnimatedValue();
            float speed = speedMultiplier;
            angle1 = (base * 0.5f * speed) % 360;
            angle2 = -(base * 0.7f * speed) % 360;
            angle3 = (base * 1.0f * speed) % 360;
            angle4 = -(base * 1.3f * speed) % 360;
            angle5 = (base * 1.8f * speed) % 360;
            invalidate();
        });
        rotationAnimator.start();
    }

    private void updatePulseAnimation() {
        if (pulseAnimator != null) {
            pulseAnimator.cancel();
        }

        float minScale, maxScale;
        long duration;

        switch (currentState) {
            case SPEAKING:
                minScale = 0.92f;
                maxScale = 1.08f;
                duration = 200;
                break;
            case LISTENING:
                minScale = 0.96f;
                maxScale = 1.04f;
                duration = 1500;
                break;
            case PROCESSING:
                minScale = 0.94f;
                maxScale = 1.06f;
                duration = 600;
                break;
            default: // IDLE
                minScale = 0.98f;
                maxScale = 1.02f;
                duration = 3000;
                break;
        }

        pulseAnimator = ValueAnimator.ofFloat(minScale, maxScale);
        pulseAnimator.setDuration(duration);
        pulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
        pulseAnimator.setRepeatMode(ValueAnimator.REVERSE);
        pulseAnimator.setInterpolator(new LinearInterpolator());
        pulseAnimator.addUpdateListener(animation -> {
            pulseScale = (float) animation.getAnimatedValue();
        });
        pulseAnimator.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float size = Math.min(getWidth(), getHeight());

        // Ambient glow
        float glowRadius = size * GLOW_RADIUS * pulseScale;
        canvas.drawCircle(cx, cy, glowRadius, glowPaint);

        // Ring 1 (outermost)
        canvas.save();
        canvas.rotate(angle1, cx, cy);
        canvas.drawCircle(cx, cy, size * RING1_RADIUS * pulseScale, ring1Paint);
        canvas.restore();

        // Ring 2
        canvas.save();
        canvas.rotate(angle2, cx, cy);
        canvas.drawCircle(cx, cy, size * RING2_RADIUS * pulseScale, ring2Paint);
        canvas.restore();

        // Ring 3 (solid with alpha gap at top/bottom)
        canvas.save();
        canvas.rotate(angle3, cx, cy);
        float r3 = size * RING3_RADIUS * pulseScale;
        canvas.drawArc(cx - r3, cy - r3, cx + r3, cy + r3, 30, 120, false, ring3Paint);
        canvas.drawArc(cx - r3, cy - r3, cx + r3, cy + r3, 210, 120, false, ring3Paint);
        canvas.restore();

        // Ring 4
        canvas.save();
        canvas.rotate(angle4, cx, cy);
        canvas.drawCircle(cx, cy, size * RING4_RADIUS * pulseScale, ring4Paint);
        canvas.restore();

        // Ring 5 (innermost ring)
        canvas.save();
        canvas.rotate(angle5, cx, cy);
        canvas.drawCircle(cx, cy, size * RING5_RADIUS * pulseScale, ring5Paint);
        canvas.restore();

        // Core circle
        float coreR = size * CORE_RADIUS * pulseScale;
        canvas.drawCircle(cx, cy, coreR, corePaint);
        canvas.drawCircle(cx, cy, coreR, coreBorderPaint);

        // ZOYA text
        textPaint.setTextSize(size * 0.04f);
        float textY = cy + (textPaint.getTextSize() / 3f);
        canvas.drawText("ZOYA", cx, textY, textPaint);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (rotationAnimator != null) rotationAnimator.cancel();
        if (pulseAnimator != null) pulseAnimator.cancel();
    }
}
