package com.google.ar.core.examples.java.helloar;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PositionOverlayView extends View {

  public enum BoxState {
    NEUTRAL,
    CORRECT,
    WRONG
  }

  public interface OnPositionTappedListener {
    void onPositionTapped(String positionCode);
  }

  private static class Box {
    final String code;
    final RectF rect;

    Box(String code, RectF rect) {
      this.code = code;
      this.rect = rect;
    }
  }

  private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

  private final List<Box> boxes = new ArrayList<>();
  private final Map<String, BoxState> stateByCode = new HashMap<>();
  /** Position code -> ULD name from load sheet (shown inside the box). */
  private final Map<String, String> expectedUldByCode = new HashMap<>();

  private OnPositionTappedListener listener;

  public PositionOverlayView(Context context) {
    super(context);
    init();
  }

  public PositionOverlayView(Context context, @Nullable AttributeSet attrs) {
    super(context, attrs);
    init();
  }

  public PositionOverlayView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);
    init();
  }

  private void init() {
    setWillNotDraw(false);

    fillPaint.setStyle(Paint.Style.FILL);

    strokePaint.setStyle(Paint.Style.STROKE);
    strokePaint.setStrokeWidth(dp(2f));

    textPaint.setColor(0xFFFFFFFF);
    textPaint.setTextAlign(Paint.Align.CENTER);
    textPaint.setTextSize(dp(14f));
    textPaint.setFakeBoldText(true);
  }

  private float dp(float value) {
    return value * getResources().getDisplayMetrics().density;
  }

  public void setOnPositionTappedListener(@Nullable OnPositionTappedListener listener) {
    this.listener = listener;
  }

  /**
   * Replace currently visible detections (screen-space).
   * @param codeToRect position code -> view rect
   * @param states position code -> box state (grey/green/red)
   * @param expectedUldByCode position code -> ULD name from load sheet (drawn inside box); may be null
   */
  public void setDetections(
      Map<String, RectF> codeToRect,
      @Nullable Map<String, BoxState> states,
      @Nullable Map<String, String> expectedUldByCode) {
    boxes.clear();
    this.expectedUldByCode.clear();

    if (states != null) {
      stateByCode.clear();
      stateByCode.putAll(states);
    }
    if (expectedUldByCode != null) {
      this.expectedUldByCode.putAll(expectedUldByCode);
    }

    if (codeToRect != null) {
      for (Map.Entry<String, RectF> e : codeToRect.entrySet()) {
        if (e.getKey() == null || e.getValue() == null) continue;
        boxes.add(new Box(e.getKey(), new RectF(e.getValue())));
      }
    }

    invalidate();
  }

  /** Update one box's state (kept even if it disappears temporarily). */
  public void setBoxState(String code, BoxState state) {
    if (code == null || state == null) return;
    stateByCode.put(code, state);
    invalidate();
  }

  public Map<String, BoxState> getStatesSnapshot() {
    return Collections.unmodifiableMap(new HashMap<>(stateByCode));
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);

    float radius = dp(10f);
    float pad = dp(4f);

    for (Box box : boxes) {
      BoxState state = stateByCode.get(box.code);
      if (state == null) state = BoxState.NEUTRAL;

      int fill;
      int stroke;
      switch (state) {
        case CORRECT:
          // Correct ULD at this slot: GREEN.
          fill = 0xCC1B5E20;   // semi-transparent green
          stroke = 0xFF1B5E20;
          break;
        case WRONG:
          // Wrong ULD at this slot: RED.
          fill = 0xCCB71C1C;   // semi-transparent red
          stroke = 0xFFB71C1C;
          break;
        case NEUTRAL:
        default:
          // Slot present in loadsheet but no ULD match yet: solid GREY.
          fill = 0xCC616161;   // semi-transparent grey
          stroke = 0xFF616161;
          break;
      }

      RectF r = new RectF(box.rect);
      r.inset(-pad, -pad);

      fillPaint.setColor(fill);
      strokePaint.setColor(stroke);

      canvas.drawRoundRect(r, radius, radius, fillPaint);
      canvas.drawRoundRect(r, radius, radius, strokePaint);

      // Center text: position code and optionally ULD name from load sheet
      float cx = r.centerX();
      float cy = r.centerY();
      Paint.FontMetrics fm = textPaint.getFontMetrics();
      float lineHeight = fm.descent - fm.ascent;
      String uldName = expectedUldByCode.get(box.code);
      if (uldName != null && !uldName.isEmpty()) {
        float textY1 = cy - lineHeight * 0.25f;
        float textY2 = cy + lineHeight * 0.75f;
        canvas.drawText(box.code, cx, textY1, textPaint);
        textPaint.setTextSize(dp(12f));
        canvas.drawText(uldName, cx, textY2, textPaint);
        textPaint.setTextSize(dp(14f));
      } else {
        float textY = cy - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(box.code, cx, textY, textPaint);
      }
    }
  }

  @Override
  public boolean onTouchEvent(MotionEvent event) {
    if (event == null) return true;
    if (event.getAction() != MotionEvent.ACTION_UP) return true;

    float x = event.getX();
    float y = event.getY();

    // Tap hit-test: pick the smallest box containing the point.
    Box hit = null;
    float hitArea = Float.MAX_VALUE;
    for (Box b : boxes) {
      if (b.rect.contains(x, y)) {
        float area = b.rect.width() * b.rect.height();
        if (area < hitArea) {
          hit = b;
          hitArea = area;
        }
      }
    }

    if (hit != null && listener != null) {
      listener.onPositionTapped(hit.code);
    }
    return true; // Always consume touches to avoid AR hit-tests.
  }
}

