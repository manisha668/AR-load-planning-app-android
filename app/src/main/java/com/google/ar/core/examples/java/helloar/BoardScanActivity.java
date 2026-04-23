/*
 * Copyright 2017 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ar.core.examples.java.helloar;

import android.graphics.Rect;
import android.graphics.RectF;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;
import android.text.SpannableString;
import android.text.style.AlignmentSpan;
import android.text.Layout;

import androidx.appcompat.app.AppCompatActivity;

import com.google.ar.core.ArCoreApk;
import com.google.ar.core.Camera;
import com.google.ar.core.Config;
import com.google.ar.core.Coordinates2d;
import com.google.ar.core.Frame;
import com.google.ar.core.Session;
import com.google.ar.core.TrackingFailureReason;
import com.google.ar.core.TrackingState;
import com.google.ar.core.examples.java.common.helpers.CameraPermissionHelper;
import com.google.ar.core.examples.java.common.helpers.DisplayRotationHelper;
import com.google.ar.core.examples.java.common.helpers.FullScreenHelper;
import com.google.ar.core.examples.java.common.helpers.SnackbarHelper;
import com.google.ar.core.examples.java.common.helpers.TrackingStateHelper;
import com.google.ar.core.examples.java.common.samplerender.SampleRender;
import com.google.ar.core.examples.java.common.samplerender.arcore.BackgroundRenderer;
import com.google.ar.core.exceptions.CameraNotAvailableException;
import com.google.ar.core.exceptions.NotYetAvailableException;
import com.google.ar.core.exceptions.UnavailableApkTooOldException;
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException;
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException;
import com.google.ar.core.exceptions.UnavailableSdkTooOldException;
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Board-based identification:
 * - Load sheet is uploaded first (HomeActivity) and passed as instructions.
 * - Live camera OCR detects position labels (e.g. 1L, 12R) and ULD text on matchboxes.
 * - Each load-sheet slot is drawn as grey until a nearby ULD is read; then green (match) or red
 *   (mismatch) vs the expected container id for that position.
 */
public class BoardScanActivity extends AppCompatActivity implements SampleRender.Renderer {
  private static final String TAG = "BoardScanActivity";

  // Intent extras (set in HomeActivity).
  private static final String EXTRA_LOAD_CONTAINERS = "LOAD_CONTAINERS";
  private static final String EXTRA_LOAD_POSITIONS = "LOAD_POSITIONS";

  /** Throttle between ML Kit OCR runs; lower = snappier ULD read but more CPU. */
  private static final long OCR_INTERVAL_MS = 220;
  private static final Pattern POSITION_CODE_PATTERN = Pattern.compile("^(\\d{1,3})([LR])$");
  // ULD IDs: e.g. AKE017373, PMC12345, ULD1. Allow up to 7 digits for long serials.
  private static final Pattern ULD_CODE_PATTERN =
      Pattern.compile("^[A-Z]{3,4}\\d{1,7}$");
  /**
   * Pull ULD-shaped tokens out of noisy OCR strings (digits may still contain I/L/O before repair).
   */
  private static final Pattern ULD_LOOSE_TOKEN_PATTERN =
      Pattern.compile("([A-Z]{3,4})([0-9IlLO]{1,8})");
  // How long to keep position boxes on screen after they temporarily disappear (to reduce flicker).
  private static final long POSITION_PERSIST_MS = 1800L;
  // Keep auto ULD match/mismatch colors briefly after OCR drops the label (same idea as position persist).
  private static final long ULD_AUTO_STATE_PERSIST_MS = 2200L;

  private GLSurfaceView surfaceView;
  private boolean installRequested;
  private boolean hasSetTextureNames = false;

  private Session session;
  private SampleRender render;
  private BackgroundRenderer backgroundRenderer;
  private DisplayRotationHelper displayRotationHelper;
  private final SnackbarHelper messageSnackbarHelper = new SnackbarHelper();
  private final TrackingStateHelper trackingStateHelper = new TrackingStateHelper(this);

  private PositionOverlayView positionOverlayView;
  private Button btnInstructions;

  private static final class Instruction {
    final String containerId;
    final String expectedPosition;

    Instruction(String containerId, String expectedPosition) {
      this.containerId = containerId;
      this.expectedPosition = expectedPosition;
    }
  }

  private final List<Instruction> instructions = new ArrayList<>();
  private final Map<String, PositionOverlayView.BoxState> boxStates = new HashMap<>();

  private TextRecognizer liveTextRecognizer;
  private final AtomicBoolean ocrInFlight = new AtomicBoolean(false);
  private volatile long lastOcrStartMs = 0L;
  private final AtomicReference<List<DetectedPosition>> latestDetections =
      new AtomicReference<>(Collections.emptyList());
  private final AtomicReference<List<DetectedUld>> latestUldDetections =
      new AtomicReference<>(Collections.emptyList());
  // For smoothing / flicker reduction.
  private final Map<String, RectF> lastViewRects = new HashMap<>();
  private final Map<String, Long> lastSeenPositionMs = new HashMap<>();
  private final Map<String, PositionOverlayView.BoxState> autoUldStickyState = new HashMap<>();
  private final Map<String, Long> autoUldStickyTimeMs = new HashMap<>();

  private static final class DetectedPosition {
    final String code;     // e.g. "12R"
    final RectF imageRect; // IMAGE_PIXELS coords (sensor space)

    DetectedPosition(String code, RectF imageRect) {
      this.code = code;
      this.imageRect = imageRect;
    }
  }

  private static final class DetectedUld {
    final String uldId;   // e.g. "AKE017373"
    final RectF imageRect;

    DetectedUld(String uldId, RectF imageRect) {
      this.uldId = uldId;
      this.imageRect = imageRect;
    }
  }

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);

    surfaceView = findViewById(R.id.surfaceview);
    displayRotationHelper = new DisplayRotationHelper(/* context= */ this);
    render = new SampleRender(surfaceView, this, getAssets());

    positionOverlayView = findViewById(R.id.position_overlay);

    View placementInfoBox = findViewById(R.id.placement_info_box);
    if (placementInfoBox != null) placementInfoBox.setVisibility(View.GONE);
    View globalWarningBanner = findViewById(R.id.global_warning_banner);
    if (globalWarningBanner != null) globalWarningBanner.setVisibility(View.GONE);

    btnInstructions = findViewById(R.id.btnInstructions);
    if (btnInstructions != null) {
      btnInstructions.setOnClickListener(v -> showInstructionsDialog(
              currentContainerIds(), currentPositions(), null));
    }

    loadInstructionsFromIntent();

    liveTextRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
    installRequested = false;
  }

  @Override
  protected void onDestroy() {
    if (liveTextRecognizer != null) {
      try {
        liveTextRecognizer.close();
      } catch (Exception ignored) {}
      liveTextRecognizer = null;
    }
    if (session != null) {
      session.close();
      session = null;
    }
    super.onDestroy();
  }

  private void loadInstructionsFromIntent() {
    ArrayList<String> containers = getIntent().getStringArrayListExtra(EXTRA_LOAD_CONTAINERS);
    ArrayList<String> positions = getIntent().getStringArrayListExtra(EXTRA_LOAD_POSITIONS);

    instructions.clear();

    if (containers == null || positions == null || containers.isEmpty() || containers.size() != positions.size()) {
      Toast.makeText(this, "Missing load sheet instructions. Go back and upload again.", Toast.LENGTH_LONG).show();
      finish();
      return;
    }

    for (int i = 0; i < containers.size(); i++) {
      String c = safeUpper(containers.get(i));
      String p = normalizePositionCode(positions.get(i));
      if (c == null || p == null) continue;
      instructions.add(new Instruction(c, p));
    }

    if (instructions.isEmpty()) {
      Toast.makeText(this, "No valid instructions found. Go back and upload again.", Toast.LENGTH_LONG).show();
      finish();
    }
  }

  private static String safeUpper(String s) {
    if (s == null) return null;
    String t = s.trim();
    if (t.isEmpty()) return null;
    return t.toUpperCase(Locale.US);
  }

  private static String normalizePositionCode(String s) {
    if (s == null) return null;
    String t = s.trim().toUpperCase(Locale.US).replaceAll("[^A-Z0-9]", "");
    if (!POSITION_CODE_PATTERN.matcher(t).matches()) return null;
    return t;
  }

  @Override
  protected void onResume() {
    super.onResume();

    if (session == null) {
      Exception exception = null;
      String message = null;
      try {
        ArCoreApk.Availability availability = ArCoreApk.getInstance().checkAvailability(this);
        if (availability != ArCoreApk.Availability.SUPPORTED_INSTALLED) {
          switch (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
            case INSTALL_REQUESTED:
              installRequested = true;
              return;
            case INSTALLED:
              break;
          }
        }

        if (!CameraPermissionHelper.hasCameraPermission(this)) {
          CameraPermissionHelper.requestCameraPermission(this);
          return;
        }

        session = new Session(/* context= */ this);
      } catch (UnavailableArcoreNotInstalledException
          | UnavailableUserDeclinedInstallationException e) {
        message = "Please install ARCore";
        exception = e;
      } catch (UnavailableApkTooOldException e) {
        message = "Please update ARCore";
        exception = e;
      } catch (UnavailableSdkTooOldException e) {
        message = "Please update this app";
        exception = e;
      } catch (UnavailableDeviceNotCompatibleException e) {
        message = "This device does not support AR";
        exception = e;
      } catch (Exception e) {
        message = "Failed to create AR session";
        exception = e;
      }

      if (message != null) {
        messageSnackbarHelper.showError(this, message);
        Log.e(TAG, "Exception creating session", exception);
        return;
      }
    }

    try {
      configureSession();
      session.resume();
    } catch (CameraNotAvailableException e) {
      messageSnackbarHelper.showError(this, "Camera not available. Try restarting the app.");
      session = null;
      return;
    }

    surfaceView.onResume();
    displayRotationHelper.onResume();
    hasSetTextureNames = false;
  }

  @Override
  public void onPause() {
    super.onPause();
    if (session != null) {
      displayRotationHelper.onPause();
      surfaceView.onPause();
      session.pause();
    }
  }

  @Override
  public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
    super.onRequestPermissionsResult(requestCode, permissions, results);
    if (!CameraPermissionHelper.hasCameraPermission(this)) {
      Toast.makeText(this, "Camera permission is needed to run this application", Toast.LENGTH_LONG).show();
      if (!CameraPermissionHelper.shouldShowRequestPermissionRationale(this)) {
        CameraPermissionHelper.launchPermissionSettings(this);
      }
      finish();
    }
  }

  @Override
  public void onWindowFocusChanged(boolean hasFocus) {
    super.onWindowFocusChanged(hasFocus);
    FullScreenHelper.setFullScreenOnWindowFocusChanged(this, hasFocus);
  }

  private void configureSession() {
    if (session == null) return;
    Config config = new Config(session);

    // Only need camera feed for board OCR; no plane finding.
    config.setPlaneFindingMode(Config.PlaneFindingMode.DISABLED);

    try {
      config.setDepthMode(Config.DepthMode.DISABLED);
    } catch (Exception ignored) {}
    try {
      config.setLightEstimationMode(Config.LightEstimationMode.DISABLED);
    } catch (Exception ignored) {}

    session.configure(config);
  }

  @Override
  public void onSurfaceCreated(SampleRender render) {
    try {
      backgroundRenderer = new BackgroundRenderer(render);
      backgroundRenderer.setUseDepthVisualization(render, /*useDepthVisualization=*/ false);
    } catch (IOException e) {
      Log.e(TAG, "Failed to initialize background renderer", e);
      messageSnackbarHelper.showError(this, "Failed to set up AR background: " + e.getMessage());
    }
  }

  @Override
  public void onSurfaceChanged(SampleRender render, int width, int height) {
    displayRotationHelper.onSurfaceChanged(width, height);
  }

  @Override
  public void onDrawFrame(SampleRender render) {
    if (session == null) return;

    if (!hasSetTextureNames && backgroundRenderer != null) {
      session.setCameraTextureNames(
          new int[] {backgroundRenderer.getCameraColorTexture().getTextureId()});
      hasSetTextureNames = true;
    }

    displayRotationHelper.updateSessionIfNeeded(session);

    Frame frame;
    try {
      frame = session.update();
    } catch (CameraNotAvailableException e) {
      Log.e(TAG, "Camera not available during onDrawFrame", e);
      messageSnackbarHelper.showError(this, "Camera not available. Try restarting the app.");
      return;
    }

    Camera camera = frame.getCamera();

    if (backgroundRenderer != null) {
      backgroundRenderer.updateDisplayGeometry(frame);

      GLES30.glDisable(GLES30.GL_DEPTH_TEST);
      GLES30.glDepthMask(false);
      GLES30.glDisable(GLES30.GL_BLEND);

      backgroundRenderer.drawBackground(render);

      GLES30.glEnable(GLES30.GL_BLEND);
      GLES30.glEnable(GLES30.GL_DEPTH_TEST);
      GLES30.glDepthMask(true);
    }

    trackingStateHelper.updateKeepScreenOnFlag(camera.getTrackingState());

    maybeRunLiveOcr(frame);
    updateOverlayFromLatestDetections(frame);

    String msg = null;
    if (camera.getTrackingState() == TrackingState.PAUSED) {
      if (camera.getTrackingFailureReason() == TrackingFailureReason.NONE) {
        msg = "Point camera at the board…";
      } else {
        msg = TrackingStateHelper.getTrackingFailureReasonString(camera);
      }
    }
    if (msg == null) {
      messageSnackbarHelper.hide(this);
    } else {
      messageSnackbarHelper.showMessage(this, msg);
    }
  }

  private void maybeRunLiveOcr(Frame frame) {
    if (liveTextRecognizer == null) return;

    long now = SystemClock.elapsedRealtime();
    if (now - lastOcrStartMs < OCR_INTERVAL_MS) return;
    if (!ocrInFlight.compareAndSet(false, true)) return;

    lastOcrStartMs = now;

    final android.media.Image cameraImage;
    try {
      cameraImage = frame.acquireCameraImage();
    } catch (NotYetAvailableException e) {
      ocrInFlight.set(false);
      return;
    } catch (Exception e) {
      Log.w(TAG, "acquireCameraImage failed", e);
      ocrInFlight.set(false);
      return;
    }

    int rotationDegrees = 0;
    try {
      String cameraId = session.getCameraConfig().getCameraId();
      rotationDegrees = displayRotationHelper.getCameraSensorToDisplayRotation(cameraId);
    } catch (Exception e) {
      Log.w(TAG, "Failed to get camera rotation", e);
    }
    final int rotationDegreesFinal = rotationDegrees;

    InputImage inputImage = InputImage.fromMediaImage(cameraImage, rotationDegreesFinal);
    liveTextRecognizer
        .process(inputImage)
        .addOnSuccessListener(
            text -> {
              // 1) Slot labels like "11R", "16R"
              latestDetections.set(
                  extractDetectedPositions(text, cameraImage, rotationDegreesFinal));
              // 2) ULD IDs like "AKE017373" on matchboxes
              latestUldDetections.set(
                  extractDetectedUlds(text, cameraImage, rotationDegreesFinal));
            })
        .addOnFailureListener(e -> Log.w(TAG, "Live OCR failed", e))
        .addOnCompleteListener(
            task -> {
              try {
                cameraImage.close();
              } catch (Exception ignored) {
              }
              ocrInFlight.set(false);
            });
  }

  private static List<DetectedPosition> extractDetectedPositions(Text text, android.media.Image cameraImage, int rotationDegrees) {
    if (text == null || cameraImage == null) return Collections.emptyList();

    int w = cameraImage.getWidth();
    int h = cameraImage.getHeight();

    Map<String, DetectedPosition> bestByCode = new HashMap<>();

    for (Text.TextBlock block : text.getTextBlocks()) {
      for (Text.Line line : block.getLines()) {
        if (line == null) continue;

        // 1) Try element-level detection first, so "12L" above "ULD1" is treated as its own box.
        for (Text.Element el : line.getElements()) {
          if (el == null) continue;
          String cleanedEl = normalizeTextToPositionCode(el.getText());
          if (cleanedEl == null) continue;

          Rect bbEl = el.getBoundingBox();
          if (bbEl == null) continue;

          RectF imageRectEl = rotatedRectToSensorRect(bbEl, rotationDegrees, w, h);
          if (imageRectEl == null) continue;

          DetectedPosition existingEl = bestByCode.get(cleanedEl);
          if (existingEl == null || area(imageRectEl) > area(existingEl.imageRect)) {
            bestByCode.put(cleanedEl, new DetectedPosition(cleanedEl, imageRectEl));
          }
        }

        // 2) Fallback: if the whole line looks like a slot code or tightly-concatenated code.
        String cleanedLine = normalizeTextToPositionCode(line.getText());
        if (cleanedLine == null) {
          StringBuilder sb = new StringBuilder();
          for (Text.Element el : line.getElements()) {
            if (el == null) continue;
            String et = el.getText();
            if (et != null) sb.append(et);
          }
          cleanedLine = normalizeTextToPositionCode(sb.toString());
        }
        if (cleanedLine == null) continue;

        Rect bbLine = line.getBoundingBox();
        if (bbLine == null) continue;

        RectF imageRectLine = rotatedRectToSensorRect(bbLine, rotationDegrees, w, h);
        if (imageRectLine == null) continue;

        DetectedPosition existingLine = bestByCode.get(cleanedLine);
        if (existingLine == null || area(imageRectLine) > area(existingLine.imageRect)) {
          bestByCode.put(cleanedLine, new DetectedPosition(cleanedLine, imageRectLine));
        }
      }
    }

    return new ArrayList<>(bestByCode.values());
  }

  private static List<DetectedUld> extractDetectedUlds(
      Text text, android.media.Image cameraImage, int rotationDegrees) {
    if (text == null || cameraImage == null) return Collections.emptyList();

    int w = cameraImage.getWidth();
    int h = cameraImage.getHeight();

    Map<String, DetectedUld> bestById = new HashMap<>();

    for (Text.TextBlock block : text.getTextBlocks()) {
      if (block == null) continue;

      for (Text.Line line : block.getLines()) {
        if (line == null) continue;

        // 1) Element-first: compact ULD print on matchboxes gets tight boxes → better pairing with slot labels.
        for (Text.Element el : line.getElements()) {
          if (el == null) continue;
          String normalized = normalizeUldText(el.getText());
          if (normalized == null) continue;
          Rect bb = el.getBoundingBox();
          if (bb == null) continue;
          RectF imageRect = rotatedRectToSensorRect(bb, rotationDegrees, w, h);
          if (imageRect == null) continue;
          putUldIfBetterLocalizer(bestById, normalized, imageRect);
        }

        // 2) Whole ML Kit line (handles spacing between prefix and digits).
        String lineNorm = normalizeUldText(line.getText());
        if (lineNorm != null) {
          Rect bb = line.getBoundingBox();
          if (bb != null) {
            RectF imageRect = rotatedRectToSensorRect(bb, rotationDegrees, w, h);
            if (imageRect != null) {
              putUldIfBetterLocalizer(bestById, lineNorm, imageRect);
            }
          }
        }

        // 3) Concatenated elements on one line (no space in OCR between "AKE" and "017373").
        StringBuilder sb = new StringBuilder();
        for (Text.Element el : line.getElements()) {
          if (el == null) continue;
          String et = el.getText();
          if (et != null) sb.append(et);
        }
        String concatNorm = normalizeUldText(sb.toString());
        if (concatNorm != null) {
          Rect union = unionElementBoundingBoxes(line);
          Rect useRect = union != null ? union : line.getBoundingBox();
          if (useRect != null) {
            RectF imageRect = rotatedRectToSensorRect(useRect, rotationDegrees, w, h);
            if (imageRect != null) {
              putUldIfBetterLocalizer(bestById, concatNorm, imageRect);
            }
          }
        }
      }
    }

    return new ArrayList<>(bestById.values());
  }

  /** Prefer the smallest bounding box per ULD id so pairing uses a center near the label, not the whole line. */
  private static void putUldIfBetterLocalizer(
      Map<String, DetectedUld> bestById, String uldId, RectF imageRect) {
    DetectedUld existing = bestById.get(uldId);
    if (existing == null || area(imageRect) < area(existing.imageRect)) {
      bestById.put(uldId, new DetectedUld(uldId, imageRect));
    }
  }

  private static Rect unionElementBoundingBoxes(Text.Line line) {
    if (line == null) return null;
    Rect u = null;
    for (Text.Element el : line.getElements()) {
      if (el == null) continue;
      Rect r = el.getBoundingBox();
      if (r == null) continue;
      if (u == null) {
        u = new Rect(r);
      } else {
        u.union(r);
      }
    }
    return u;
  }

  private static float area(RectF r) {
    return Math.max(0f, r.width()) * Math.max(0f, r.height());
  }

  /**
   * Normalizes raw OCR text into a strict slot code: {@code 1..999 + (L|R)}.
   *
   * <p>Fixes common OCR confusions seen on handwritten labels, e.g.:
   * "IIR" -> "11R", "1IR" -> "11R", "12 L" -> "12L".
   */
  private static String normalizeTextToPositionCode(String s) {
    String pre = s.replace('|', '1').replace('‖', '1').replace('—', '-');
    if (s == null) return null;
    String t = s.trim().toUpperCase(Locale.US).replaceAll("[^A-Z0-9]", "");
    if (t.isEmpty()) return null;

    // Fast-path: already valid.
    if (POSITION_CODE_PATTERN.matcher(t).matches()) return t;

    // Expected format is digits + L/R at the end. Try to repair OCR confusions in the digit part.
    if (t.length() >= 2) {
      char last = t.charAt(t.length() - 1);
      String head = t.substring(0, t.length() - 1);

      if (last == 'L' || last == 'R') {
        String fixedHead = normalizeDigitsFromOcr(head);
        String candidate = fixedHead + last;
        if (POSITION_CODE_PATTERN.matcher(candidate).matches()) return candidate;
      }

      // Sometimes trailing 'L' is misread as '1' (especially if the label is handwritten).
      if (last == '1' || last == 'I') {
        String fixedHead = normalizeDigitsFromOcr(head);
        String candidate = fixedHead + 'L';
        if (POSITION_CODE_PATTERN.matcher(candidate).matches()) return candidate;
      }
    }

    // Sometimes OCR reverses order (e.g., "R11"). Repair that too.
    if (t.length() >= 2) {
      char first = t.charAt(0);
      if (first == 'L' || first == 'R') {
        String fixedDigits = normalizeDigitsFromOcr(t.substring(1));
        String candidate = fixedDigits + first;
        if (POSITION_CODE_PATTERN.matcher(candidate).matches()) return candidate;
      }
    }

    return null;
  }

  private static String normalizeDigitsFromOcr(String digits) {
    if (digits == null) return "";
    // Only apply to the numeric part (before L/R).
    return digits
        .replace('I', '1')
        .replace('L', '1')
        .replace('O', '0');
  }

  /**
   * Normalizes raw OCR text into a ULD ID (e.g., AKE017373), including embedded tokens and common
   * digit confusions (O/0, I/1, L/1) in the numeric suffix.
   */
  private static String normalizeUldText(String s) {
    if (s == null) return null;
    String t = s.trim().toUpperCase(Locale.US).replaceAll("[^A-Z0-9]", "");
    if (t.isEmpty()) return null;

    String direct = repairUldCandidate(t);
    if (direct != null) return direct;

    String best = null;
    Matcher m = ULD_LOOSE_TOKEN_PATTERN.matcher(t);
    while (m.find()) {
      String letters = m.group(1);
      String digitPart = normalizeDigitsFromOcr(m.group(2));
      if (digitPart.isEmpty()) continue;
      String candidate = letters + digitPart;
      if (!ULD_CODE_PATTERN.matcher(candidate).matches()) continue;
      if (best == null || candidate.length() > best.length()) {
        best = candidate;
      }
    }
    return best;
  }

  /** Applies digit OCR fixes to a string that is already [A-Z]{3,4}\\d+. */
  private static String repairUldCandidate(String alnumUpper) {
    Matcher m = ULD_LOOSE_TOKEN_PATTERN.matcher(alnumUpper);
    if (!m.matches()) return null;
    String letters = m.group(1);
    String digitPart = normalizeDigitsFromOcr(m.group(2));
    if (digitPart.isEmpty()) return null;
    String candidate = letters + digitPart;
    return ULD_CODE_PATTERN.matcher(candidate).matches() ? candidate : null;
  }

  /** Canonicalize ULD IDs for comparisons (strip spaces/dashes/punctuation). */
  private static String canonicalizeUldId(String s) {
    if (s == null) return null;
    String t = s.trim().toUpperCase(Locale.US).replaceAll("[^A-Z0-9]", "");
    return t.isEmpty() ? null : t;
  }

  private static RectF rotatedRectToSensorRect(Rect rotatedRect, int rotationDegrees, int sensorW, int sensorH) {
    if (rotatedRect == null) return null;

    float l = rotatedRect.left;
    float t = rotatedRect.top;
    float r = rotatedRect.right;
    float b = rotatedRect.bottom;

    float[] p1 = mapRotatedToSensor(l, t, rotationDegrees, sensorW, sensorH);
    float[] p2 = mapRotatedToSensor(r, t, rotationDegrees, sensorW, sensorH);
    float[] p3 = mapRotatedToSensor(r, b, rotationDegrees, sensorW, sensorH);
    float[] p4 = mapRotatedToSensor(l, b, rotationDegrees, sensorW, sensorH);

    float minX = min(p1[0], p2[0], p3[0], p4[0]);
    float minY = min(p1[1], p2[1], p3[1], p4[1]);
    float maxX = max(p1[0], p2[0], p3[0], p4[0]);
    float maxY = max(p1[1], p2[1], p3[1], p4[1]);

    return new RectF(minX, minY, maxX, maxY);
  }

  // Map point in ML Kit rotated image space back to camera sensor image space (IMAGE_PIXELS).
  private static float[] mapRotatedToSensor(float x, float y, int rotationDegrees, int w, int h) {
    switch (rotationDegrees) {
      case 0:
        return new float[] {x, y};
      case 90:
        // Rotated image is sensor rotated clockwise 90deg.
        // Invert: (x_s, y_s) = (y_r, h - x_r)
        return new float[] {y, h - x};
      case 180:
        return new float[] {w - x, h - y};
      case 270:
        // Rotated image is sensor rotated clockwise 270deg (i.e., CCW 90deg).
        // Invert: (x_s, y_s) = (w - y_r, x_r)
        return new float[] {w - y, x};
      default:
        return new float[] {x, y};
    }
  }

  private static float min(float a, float b, float c, float d) {
    return Math.min(Math.min(a, b), Math.min(c, d));
  }


  /**
   * Build and show the instructions dialog. onDismiss is called after user taps OK or closes.
   */
  private void showInstructionsDialog(List<String> containers,
                                      List<String> positions,
                                      Runnable onDismiss) {
    if (containers == null || containers.isEmpty()) return;
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < containers.size(); i++) {
      sb.append(containers.get(i)).append('-').append(positions.get(i));
      if (i < containers.size() - 1) {
        sb.append("\n");
      }
    }
    SpannableString msg = new SpannableString(sb.toString());
    msg.setSpan(new AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER),
                0, msg.length(), 0);
    new androidx.appcompat.app.AlertDialog.Builder(this)
        .setTitle("Loaded instructions")
        .setMessage(msg)
        .setPositiveButton("OK", (d, w) -> {
          if (onDismiss != null) onDismiss.run();
        })
        .setOnDismissListener(d -> {
          if (onDismiss != null) onDismiss.run();
        })
        .show();
  }

  private ArrayList<String> currentContainerIds() {
    ArrayList<String> ids = new ArrayList<>();
    for (Instruction ins : instructions) {
      ids.add(ins.containerId);
    }
    return ids;
  }

  private ArrayList<String> currentPositions() {
    ArrayList<String> pos = new ArrayList<>();
    for (Instruction ins : instructions) {
      pos.add(ins.expectedPosition);
    }
    return pos;
  }

  private static float max(float a, float b, float c, float d) {
    return Math.max(Math.max(a, b), Math.max(c, d));
  }

  // Linear interpolation between two rects.
  private static RectF lerpRect(RectF from, RectF to, float alpha) {
    if (from == null) return new RectF(to);
    alpha = Math.max(0f, Math.min(1f, alpha));
    float inv = 1f - alpha;
    return new RectF(
        from.left * inv + to.left * alpha,
        from.top * inv + to.top * alpha,
        from.right * inv + to.right * alpha,
        from.bottom * inv + to.bottom * alpha);
  }

  private void updateOverlayFromLatestDetections(Frame frame) {
    if (positionOverlayView == null || frame == null) return;

    long now = SystemClock.elapsedRealtime();

    List<DetectedPosition> detections = latestDetections.get();
    if (detections == null) detections = Collections.emptyList();

    Map<String, RectF> viewRects = new HashMap<>();
    Map<String, DetectedPosition> positionByCode = new HashMap<>();
    for (DetectedPosition dp : detections) {
      if (dp == null || dp.code == null || dp.imageRect == null) continue;
      // Only positions that exist in the load sheet should be highlighted.
      if (!hasInstructionForPosition(dp.code)) continue;

      positionByCode.put(dp.code, dp);
      // Only visualize positions that appear in the load sheet.
      if (!hasInstructionForPosition(dp.code)) continue;

      float[] in = new float[] {
          dp.imageRect.left, dp.imageRect.top,
          dp.imageRect.right, dp.imageRect.top,
          dp.imageRect.right, dp.imageRect.bottom,
          dp.imageRect.left, dp.imageRect.bottom
      };
      float[] out = new float[8];
      try {
        frame.transformCoordinates2d(Coordinates2d.IMAGE_PIXELS, in, Coordinates2d.VIEW, out);
      } catch (Exception e) {
        continue;
      }

      float minX = min(out[0], out[2], out[4], out[6]);
      float minY = min(out[1], out[3], out[5], out[7]);
      float maxX = max(out[0], out[2], out[4], out[6]);
      float maxY = max(out[1], out[3], out[5], out[7]);

      RectF viewRect = new RectF(minX, minY, maxX, maxY);
      // Smooth movement by blending with the previous rect (if any).
      RectF prev = lastViewRects.get(dp.code);
      if (prev != null) {
        viewRect = lerpRect(prev, viewRect, 0.35f);
      }

      RectF existing = viewRects.get(dp.code);
      if (existing == null || area(viewRect) > area(existing)) {
        viewRects.put(dp.code, viewRect);
      }

      lastSeenPositionMs.put(dp.code, now);
    }

    // Keep recently-seen boxes for a short time even if OCR misses them this frame.
    for (Map.Entry<String, RectF> e : lastViewRects.entrySet()) {
      String code = e.getKey();
      if (viewRects.containsKey(code)) continue;
      Long lastSeen = lastSeenPositionMs.get(code);
      if (lastSeen == null) continue;
      if (now - lastSeen <= POSITION_PERSIST_MS) {
        viewRects.put(code, e.getValue());
      }
    }

    // Auto-evaluate ULD placement: compare detected ULD IDs against expected ULDs from load sheet.
    autoEvaluateUldPlacement(positionByCode);

    // Update history for next frame's smoothing.
    lastViewRects.clear();
    lastViewRects.putAll(viewRects);

    // Build position -> ULD name (from load sheet) for display inside each box.
    Map<String, String> expectedUldDisplayByPosition = new HashMap<>();
    for (Instruction ins : instructions) {
      if (ins != null && ins.expectedPosition != null && ins.containerId != null) {
        expectedUldDisplayByPosition.put(
            ins.expectedPosition.toUpperCase(Locale.US),
            ins.containerId.trim());
      }
    }

    final Map<String, String> uldDisplay = expectedUldDisplayByPosition;
    runOnUiThread(() -> positionOverlayView.setDetections(viewRects, boxStates, uldDisplay));
  }

  /**
   * For each detected ULD matchbox, find the nearest position label and
   * mark that slot as CORRECT (green) or WRONG (red) based on the load sheet.
   *
   * <p>OCR often drops the ULD for a frame; without persistence, green/red would flicker off.
   */
  private void autoEvaluateUldPlacement(Map<String, DetectedPosition> positionByCode) {
    List<DetectedUld> ulds = latestUldDetections.get();

    Map<String, String> expectedUldByPosition = new HashMap<>();
    for (Instruction ins : instructions) {
      if (ins == null || ins.expectedPosition == null || ins.containerId == null) continue;
      String pos = ins.expectedPosition.toUpperCase(Locale.US);
      String uld = canonicalizeUldId(ins.containerId);
      if (uld == null) continue;
      expectedUldByPosition.put(pos, uld);
    }

    Map<String, PositionOverlayView.BoxState> fresh = new HashMap<>();
    if (ulds != null) {
      for (DetectedUld uld : ulds) {
        if (uld == null || uld.uldId == null || uld.imageRect == null) continue;

        String nearestCode = null;
        float nearestDistSq = Float.MAX_VALUE;
        float uldCx = uld.imageRect.centerX();
        float uldCy = uld.imageRect.centerY();

        for (Map.Entry<String, DetectedPosition> e : positionByCode.entrySet()) {
          DetectedPosition dp = e.getValue();
          if (dp == null || dp.imageRect == null) continue;
          float cx = dp.imageRect.centerX();
          float cy = dp.imageRect.centerY();
          float dx = cx - uldCx;
          float dy = cy - uldCy;
          float distSq = dx * dx + dy * dy;
          if (distSq < nearestDistSq) {
            nearestDistSq = distSq;
            nearestCode = e.getKey();
          }
        }

        if (nearestCode == null) continue;

        DetectedPosition nearestPos = positionByCode.get(nearestCode);
        if (nearestPos == null || nearestPos.imageRect == null) continue;
        RectF posRect = nearestPos.imageRect;
        RectF expanded = new RectF(posRect);
        float expandX = posRect.width();
        float expandY = posRect.height();
        expanded.inset(-expandX, -expandY);
        if (!expanded.contains(uldCx, uldCy)) {
          continue;
        }

        String expectedUld = expectedUldByPosition.get(nearestCode.toUpperCase(Locale.US));
        if (expectedUld == null) continue;

        String detectedUld = canonicalizeUldId(uld.uldId);
        if (detectedUld == null) continue;
        boolean match = detectedUld.equals(expectedUld);

        Log.d(
            TAG,
            "ULD match check: position="
                + nearestCode
                + " expected="
                + expectedUld
                + " detected="
                + detectedUld
                + " -> "
                + (match ? "CORRECT" : "WRONG"));

        fresh.put(
            nearestCode,
            match ? PositionOverlayView.BoxState.CORRECT : PositionOverlayView.BoxState.WRONG);
      }
    }

    long now = SystemClock.elapsedRealtime();

    for (String code : positionByCode.keySet()) {
      PositionOverlayView.BoxState state;
      if (fresh.containsKey(code)) {
        state = fresh.get(code);
        autoUldStickyState.put(code, state);
        autoUldStickyTimeMs.put(code, now);
      } else {
        PositionOverlayView.BoxState prev = autoUldStickyState.get(code);
        Long t = autoUldStickyTimeMs.get(code);
        if (prev != null
            && t != null
            && (prev == PositionOverlayView.BoxState.CORRECT
                || prev == PositionOverlayView.BoxState.WRONG)
            && (now - t) <= ULD_AUTO_STATE_PERSIST_MS) {
          state = prev;
        } else {
          state = PositionOverlayView.BoxState.NEUTRAL;
          autoUldStickyState.remove(code);
          autoUldStickyTimeMs.remove(code);
        }
      }
      boxStates.put(code, state);
    }

    autoUldStickyState.keySet().removeIf(c -> !positionByCode.containsKey(c));
    autoUldStickyTimeMs.keySet().removeIf(c -> !positionByCode.containsKey(c));
  }

  /** Returns true if any instruction in the load sheet uses this position code. */
  private boolean hasInstructionForPosition(String code) {
    if (code == null) return false;
    if (instructions == null || instructions.isEmpty()) return false;
    for (Instruction ins : instructions) {
      if (ins != null && ins.expectedPosition != null
          && code.equalsIgnoreCase(ins.expectedPosition)) {
        return true;
      }
    }
    return false;
  }
}

