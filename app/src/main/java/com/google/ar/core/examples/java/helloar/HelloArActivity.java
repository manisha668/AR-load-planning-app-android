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

import com.google.ar.core.Pose;
import android.media.Image;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.Bundle;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Toast;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.google.ar.core.Anchor;
import com.google.ar.core.ArCoreApk;
import com.google.ar.core.ArCoreApk.Availability;
import com.google.ar.core.Camera;
import com.google.ar.core.Config;
import com.google.ar.core.Config.InstantPlacementMode;
import com.google.ar.core.DepthPoint;
import com.google.ar.core.Frame;
import com.google.ar.core.HitResult;
import com.google.ar.core.InstantPlacementPoint;
import com.google.ar.core.Plane;
import com.google.ar.core.Point;
import com.google.ar.core.Point.OrientationMode;
import com.google.ar.core.Session;
import com.google.ar.core.Trackable;
import com.google.ar.core.TrackingFailureReason;
import com.google.ar.core.TrackingState;
import com.google.ar.core.examples.java.common.helpers.CameraPermissionHelper;
import com.google.ar.core.examples.java.common.helpers.DisplayRotationHelper;
import com.google.ar.core.examples.java.common.helpers.FullScreenHelper;
import com.google.ar.core.examples.java.common.helpers.SnackbarHelper;
import com.google.ar.core.examples.java.common.helpers.TapHelper;
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
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import android.widget.EditText;


/**
 * This is a simple example that shows how to create an augmented reality (AR) application using the
 * ARCore API. The application will display any detected planes and will allow the user to tap on a
 * plane to place a 3D model.
 */
public class HelloArActivity extends AppCompatActivity implements SampleRender.Renderer {
  // --- Ramp / loading logic ---
  // Reference anchor for the aircraft ramp (local coordinate system).
  private Anchor rampAnchor = null;
  
  // Aircraft configuration (from user selection)
  private AircraftConfig aircraftConfig;
  
  // Coordinate converter (created after ramp anchor is established)
  private RampCoordinateConverter coordinateConverter = null;
  
  // Evaluator that turns coordinates into logical ramp positions and checks the plan.
  private LoadingPlan loadingPlan;
  private PlacementEvaluator placementEvaluator;

  /**
   * Simple demo-guided mode: we keep a list of target containers and slots
   * derived from the aircraft ramp layout, and cycle through them. Before tap,
   * the UI tells you which container and slot you should place.
   */
  private static class DemoTarget {
    final String containerId;
    final float weightKg;
    final String positionCode; // e.g. "2R"

    DemoTarget(String containerId, float weightKg, String positionCode) {
      this.containerId = containerId;
      this.weightKg = weightKg;
      this.positionCode = positionCode;
    }
  }

  private final List<DemoTarget> demoTargets = new ArrayList<>();
  private int currentDemoIndex = 0;

  private static final String TAG = HelloArActivity.class.getSimpleName();

  private static final String SEARCHING_PLANE_MESSAGE = "Searching for surfaces...";
  private static final String WAITING_FOR_TAP_MESSAGE = "Tap on a surface to place an object.";

  // Optional: anchor for suggested better placement visualization.
  private Anchor suggestionAnchor = null;
  private TextView placementInfoBox;

  // Top warning banner shown when a mismatch is detected.
  private TextView globalWarningBanner;
  
  // 2D overlay labels positioned in world space
  private final List<OverlayLabel> overlayLabels = new ArrayList<>();
  private android.widget.RelativeLayout overlayContainer;

  private static final float Z_NEAR = 0.1f;
  private static final float Z_FAR = 100f;

  // Rendering. The Renderers are created here, and initialized when the GL surface is created.
  private GLSurfaceView surfaceView;

  private boolean installRequested;

  private Session session;
  private final SnackbarHelper messageSnackbarHelper = new SnackbarHelper();
  private DisplayRotationHelper displayRotationHelper;
  private final TrackingStateHelper trackingStateHelper = new TrackingStateHelper(this);
  private TapHelper tapHelper;
  private SampleRender render;

  private BackgroundRenderer backgroundRenderer;
  private boolean hasSetTextureNames = false;

  // Temporary matrix allocated here to reduce number of allocations for each frame.
  private final float[] viewMatrix = new float[16];
  private final float[] projectionMatrix = new float[16];

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);
    surfaceView = findViewById(R.id.surfaceview);
    displayRotationHelper = new DisplayRotationHelper(/* context= */ this);

    // Set up touch listener.
    tapHelper = new TapHelper(/* context= */ this);
    surfaceView.setOnTouchListener(tapHelper);

    // Set up renderer.
    render = new SampleRender(surfaceView, this, getAssets());

    installRequested = false;

    // Overlay container and info/banner views defined in activity_main.xml
    overlayContainer = findViewById(R.id.overlay_container);
    placementInfoBox = findViewById(R.id.placement_info_box);
    globalWarningBanner = findViewById(R.id.global_warning_banner);

    // Get aircraft type from intent (passed from HomeActivity)
    String aircraftTypeName = getIntent().getStringExtra("AIRCRAFT_TYPE");
    AircraftConfig.AircraftType aircraftType = AircraftConfig.AircraftType.AIRCRAFT_A; // default
    if (aircraftTypeName != null) {
      try {
        aircraftType = AircraftConfig.AircraftType.valueOf(aircraftTypeName);
      } catch (IllegalArgumentException e) {
        Log.w(TAG, "Invalid aircraft type: " + aircraftTypeName + ", using default");
      }
    }
    aircraftConfig = AircraftConfig.getConfig(aircraftType);
    Log.i(TAG, "Using aircraft: " + aircraftConfig.getType().getDisplayName() + 
               ", rows=" + aircraftConfig.getTotalRows());

    // Create loading plan from bundled sample file and apply aircraft-specific weight limits.
    try {
      loadingPlan = LoadingPlanParser.parseAsset(this, "sample_loading_plan.txt");
    } catch (IOException e) {
      Log.w(TAG, "Failed to load sample_loading_plan.txt from assets, falling back to demo plan", e);
      loadingPlan = LoadingPlan.createDemoPlan();
    }
    aircraftConfig.applyWeightLimitsTo(loadingPlan);
    placementEvaluator = new PlacementEvaluator(loadingPlan, aircraftConfig.getTotalRows());

    // Initialize guided demo targets based on ramp layout and show the first target.
    initDemoTargets();
    updateCurrentTargetBanner();
  }

  @Override
  protected void onDestroy() {
    if (session != null) {
      // Explicitly close ARCore Session to release native resources.
      // Review the API reference for important considerations before calling close() in apps with
      // more complicated lifecycle requirements:
      // https://developers.google.com/ar/reference/java/arcore/reference/com/google/ar/core/Session#close()
      session.close();
      session = null;
    }

    super.onDestroy();
  }

  @Override
  protected void onResume() {
    super.onResume();

    if (session == null) {
      Exception exception = null;
      String message = null;
      try {
        // Always check the latest availability.
        Availability availability = ArCoreApk.getInstance().checkAvailability(this);

        // In all other cases, try to install ARCore and handle installation failures.
        if (availability != Availability.SUPPORTED_INSTALLED) {
          switch (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
            case INSTALL_REQUESTED:
              installRequested = true;
              return;
            case INSTALLED:
              break;
          }
        }

        // ARCore requires camera permissions to operate. If we did not yet obtain runtime
        // permission on Android M and above, now is a good time to ask the user for it.
        if (!CameraPermissionHelper.hasCameraPermission(this)) {
          CameraPermissionHelper.requestCameraPermission(this);
          return;
        }

        // Create the session.
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

    // Note that order matters - see the note in onPause(), the reverse applies here.
    try {
      configureSession();
      // To record a live camera session for later playback, call
      // `session.startRecording(recordingConfig)` at anytime. To playback a previously recorded AR
      // session instead of using the live camera feed, call
      // `session.setPlaybackDatasetUri(Uri)` before calling `session.resume()`. To
      // learn more about recording and playback, see:
      // https://developers.google.com/ar/develop/java/recording-and-playback
      session.resume();
    } catch (CameraNotAvailableException e) {
      messageSnackbarHelper.showError(this, "Camera not available. Try restarting the app.");
      session = null;
      return;
    }

    surfaceView.onResume();
    displayRotationHelper.onResume();


  }

  @Override
  public void onPause() {
    super.onPause();
    if (session != null) {
      // Note that the order matters - GLSurfaceView is paused first so that it does not try
      // to query the session. If Session is paused before GLSurfaceView, GLSurfaceView may
      // still call session.update() and get a SessionPausedException.
      displayRotationHelper.onPause();
      surfaceView.onPause();
      session.pause();
    }
  }

  @Override
  public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
    super.onRequestPermissionsResult(requestCode, permissions, results);
    if (!CameraPermissionHelper.hasCameraPermission(this)) {
      // Use toast instead of snackbar here since the activity will exit.
      Toast.makeText(this, "Camera permission is needed to run this application", Toast.LENGTH_LONG)
          .show();
      if (!CameraPermissionHelper.shouldShowRequestPermissionRationale(this)) {
        // Permission denied with checking "Do not ask again".
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

  @Override
  public void onSurfaceCreated(SampleRender render) {
    // Only set up the camera background; no 3D objects or point clouds.
    try {
      backgroundRenderer = new BackgroundRenderer(render);
      // Use standard camera image background (no depth visualization).
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

  /** Build a simple list of demo containers mapped to ramp positions. */
  private void initDemoTargets() {
    demoTargets.clear();
    int rows = aircraftConfig.getTotalRows();

    // For each row and side, create a synthetic container ID and assign a
    // weight safely under the structural limit for that slot.
    for (int row = 1; row <= rows; row++) {
      for (RampPosition.Side side : RampPosition.Side.values()) {
        String positionCode = row + side.name(); // e.g. "2L" or "3R"
        float limit = aircraftConfig.getWeightLimitForPosition(positionCode);
        float weight = limit == Float.MAX_VALUE ? 3000f : Math.max(500f, Math.min(limit - 100f, limit));
        String containerId = "ULD-" + positionCode; // e.g. ULD-2L
        demoTargets.add(new DemoTarget(containerId, weight, positionCode));
      }
    }
    currentDemoIndex = 0;
  }

  private DemoTarget getCurrentDemoTarget() {
    if (demoTargets.isEmpty()) return null;
    return demoTargets.get(currentDemoIndex % demoTargets.size());
  }

  private void advanceDemoTarget() {
    if (demoTargets.isEmpty()) return;
    currentDemoIndex = (currentDemoIndex + 1) % demoTargets.size();
    updateCurrentTargetBanner();
  }

  /** Show which container and slot the user should place next. */
  private void updateCurrentTargetBanner() {
    final DemoTarget t = getCurrentDemoTarget();
    if (t == null || placementInfoBox == null) return;
    runOnUiThread(() -> {
      placementInfoBox.setText("NEXT: Place " + t.containerId + " at " + t.positionCode);
      placementInfoBox.setBackgroundColor(0xCC37474F); // neutral dark gray
    });
  }

  @Override
  public void onDrawFrame(SampleRender render) {
    if (session == null) {
      return;
    }

    // Texture names should only be set once on a GL thread unless they change.
    if (!hasSetTextureNames && backgroundRenderer != null) {
      session.setCameraTextureNames(
          new int[] {backgroundRenderer.getCameraColorTexture().getTextureId()});
      hasSetTextureNames = true;
    }

    // Notify ARCore session that the view size changed so that the perspective matrix and
    // the video background can be properly adjusted.
    displayRotationHelper.updateSessionIfNeeded(session);

    // Obtain the current frame from the AR Session.
    Frame frame;
    try {
      frame = session.update();
    } catch (CameraNotAvailableException e) {
      Log.e(TAG, "Camera not available during onDrawFrame", e);
      messageSnackbarHelper.showError(this, "Camera not available. Try restarting the app.");
      return;
    }
    Camera camera = frame.getCamera();

    // Update the background geometry (camera image on the surface).
    if (backgroundRenderer != null) {
      backgroundRenderer.updateDisplayGeometry(frame);
    }

    // Ensure ramp anchor is established on first tracking plane.
    ensureRampAnchor();

    // Handle one tap per frame.
    handleTap(frame, camera);

    // Keep the screen unlocked while tracking, but allow it to lock when tracking stops.
    trackingStateHelper.updateKeepScreenOnFlag(camera.getTrackingState());

    // Show a message based on whether tracking has failed, if planes are detected, and if the user
    // has placed any objects.
    String message = null;
    if (camera.getTrackingState() == TrackingState.PAUSED) {
      if (camera.getTrackingFailureReason() == TrackingFailureReason.NONE) {
        message = SEARCHING_PLANE_MESSAGE;
      } else {
        message = TrackingStateHelper.getTrackingFailureReasonString(camera);
      }
    } else if (hasTrackingPlane()) {
      if (overlayLabels.isEmpty()) {
        message = WAITING_FOR_TAP_MESSAGE;
      }
    } else {
      message = SEARCHING_PLANE_MESSAGE;
    }
    if (message == null) {
      messageSnackbarHelper.hide(this);
    } else {
      messageSnackbarHelper.showMessage(this, message);
    }

    // Draw the camera background.
    if (frame.getTimestamp() != 0 && backgroundRenderer != null) {
      backgroundRenderer.drawBackground(render);
    }

    // If not tracking, do not attempt to project labels.
    if (camera.getTrackingState() == TrackingState.PAUSED) {
      return;
    }

    // Compute current projection + view matrices for label projection.
    camera.getProjectionMatrix(projectionMatrix, 0, Z_NEAR, Z_FAR);
    camera.getViewMatrix(viewMatrix, 0);

    // Update 2D overlay labels positions based on world-to-screen projection.
    updateOverlayLabels(frame, camera, projectionMatrix, viewMatrix);
  }
  
  /**
   * Projects world positions to screen coordinates and updates overlay label positions.
   */
  private void updateOverlayLabels(Frame frame, Camera camera, float[] projectionMatrix, float[] viewMatrix) {
    if (overlayContainer == null || overlayLabels.isEmpty()) {
      return;
    }

    int[] viewport = new int[4];
    GLES30.glGetIntegerv(GLES30.GL_VIEWPORT, viewport, 0);
    int screenWidth = viewport[2];
    int screenHeight = viewport[3];

    float[] mvpMatrix = new float[16];
    Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, viewMatrix, 0);

    runOnUiThread(() -> {
      for (OverlayLabel label : overlayLabels) {
        if (!label.isVisible() || label.getWorldPose() == null) {
          if (label.getView() != null) {
            label.getView().setVisibility(View.GONE);
          }
          continue;
        }

        // Project world position to screen coordinates
        float[] worldPos = label.getWorldPose().getTranslation();
        float[] screenPos = new float[4];
        Matrix.multiplyMV(screenPos, 0, mvpMatrix, 0,
            new float[]{worldPos[0], worldPos[1], worldPos[2], 1.0f}, 0);

        if (screenPos[3] <= 0) {
          // Behind camera, hide label
          if (label.getView() != null) {
            label.getView().setVisibility(View.GONE);
          }
          continue;
        }

        // Normalize to NDC
        float ndcX = screenPos[0] / screenPos[3];
        float ndcY = screenPos[1] / screenPos[3];

        // Convert to screen coordinates
        int screenX = (int) ((ndcX + 1.0f) * 0.5f * screenWidth);
        int screenY = (int) ((1.0f - ndcY) * 0.5f * screenHeight); // Flip Y

        // Get or create TextView for this label
        TextView labelView = label.getView();
        if (labelView == null) {
          labelView = new TextView(this);
          labelView.setPadding(24, 16, 24, 16);
          labelView.setTextSize(13);
          labelView.setTypeface(null, android.graphics.Typeface.BOLD);
          labelView.setMaxLines(3);
          overlayContainer.addView(labelView);
          label.setView(labelView);
        }

        // Update style based on label color (error vs ok vs neutral)
        int bg = label.getBackgroundColor();
        if (bg == 0xCCB71C1C) { // error red
          labelView.setBackgroundResource(R.drawable.bg_label_error);
          labelView.setTextColor(0xFFFFFFFF);
        } else if (bg == 0xCC1B5E20) { // ok green
          labelView.setBackgroundResource(R.drawable.bg_label_ok);
          labelView.setTextColor(0xFFFFFFFF);
        } else {
          labelView.setBackgroundResource(R.drawable.bg_info_card);
          labelView.setTextColor(label.getTextColor());
        }

        labelView.setText(label.getText());
        labelView.setVisibility(View.VISIBLE);

        // Position the view
        android.widget.RelativeLayout.LayoutParams params =
            (android.widget.RelativeLayout.LayoutParams) labelView.getLayoutParams();
        if (params == null) {
          params = new android.widget.RelativeLayout.LayoutParams(
              android.widget.RelativeLayout.LayoutParams.WRAP_CONTENT,
              android.widget.RelativeLayout.LayoutParams.WRAP_CONTENT);
        }

        // Center the label on the projected point
        labelView.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED),
            android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED));
        int labelWidth = labelView.getMeasuredWidth();
        int labelHeight = labelView.getMeasuredHeight();

        params.leftMargin = screenX - labelWidth / 2;
        params.topMargin = screenY - labelHeight / 2;
        labelView.setLayoutParams(params);
      }
    });
  }

  // Handle only one tap per frame, as taps are usually low frequency compared to frame rate.
  private void handleTap(Frame frame, Camera camera) {
    MotionEvent tap = tapHelper.poll();
    if (tap == null || camera.getTrackingState() != TrackingState.TRACKING) {
      return;
    }

    // Standard ARCore hit test against detected geometry.
    List<HitResult> hitResultList = frame.hitTest(tap);

    for (HitResult hit : hitResultList) {
      Trackable trackable = hit.getTrackable();

      if ((trackable instanceof Plane
              && ((Plane) trackable).isPoseInPolygon(hit.getHitPose()))
              || (trackable instanceof Point
              && ((Point) trackable).getOrientationMode()
              == OrientationMode.ESTIMATED_SURFACE_NORMAL)
              || (trackable instanceof InstantPlacementPoint)
              || (trackable instanceof DepthPoint)) {

        // Create ramp anchor on first tap if not yet created
        if (trackable instanceof Plane) {
          if (!createRampAnchorFromTap(hit, (Plane) trackable)) {
            return;
          }
        }

        if (coordinateConverter == null) {
          // Coordinate converter not ready yet (ramp anchor not established).
          return;
        }

        // 1) Get TAP position in world coordinates.
        Pose hitPose = hit.getHitPose();
        float[] tapPos = hitPose.getTranslation();
        Log.d(TAG, "Tap world position: (" + tapPos[0] + ", " + tapPos[1] + ", " + tapPos[2] + ")");

        // 2) Convert world pose to ramp position using coordinate converter.
        RampPosition rampPosition = coordinateConverter.worldPoseToRampPosition(hitPose);
        if (rampPosition == null) {
          // Object is outside the modeled ramp area; ignore this tap for placement logic.
          Log.d(TAG, "Tap outside ramp bounds; ignoring for ramp logic.");
          continue;
        }

        // Debug: log local and normalized coordinates for calibration
        float[] localCoords = coordinateConverter.toRampLocal(hitPose);
        float[] normCoords = coordinateConverter.normalize(localCoords);
        Log.d(TAG, String.format(
            "RAMP DEBUG: local=(%.3f, %.3f), norm=(%.3f, %.3f), slot=%s",
            localCoords[0], localCoords[2], normCoords[0], normCoords[1], rampPosition.asCode()));

        Log.d(TAG, "Ramp position: " + rampPosition.asCode());

        // 3) Guided demo container identity & weight.
        DemoTarget target = getCurrentDemoTarget();
        if (target == null) {
          Log.w(TAG, "No demo targets configured; skipping evaluation.");
          continue;
        }
        String containerId = target.containerId;
        float containerWeightKg = target.weightKg;

        // Ensure loading plan knows the expected slot for this demo container.
        loadingPlan.putEntry(new LoadingPlan.PlanEntry(
            containerId,
            containerWeightKg,
            target.positionCode));

        // 4) Evaluate placement against plan & limits.
        PlacementEvaluator.Result result =
            placementEvaluator.evaluate(containerId, containerWeightKg, rampPosition);

        // Extra debug: log evaluation details.
        String expectedCode = (result.expectedPosition != null)
            ? result.expectedPosition.asCode()
            : "<none>";
        Log.d(TAG, String.format(
            "EVAL DEBUG: id=%s actual=%s expected=%s posMatch=%s weightOk=%s",
            containerId,
            rampPosition.asCode(),
            expectedCode,
            result.positionMatchesPlan,
            result.withinWeightLimit));

        // 5) Create 2D overlay label at tap position
        final String overlayText;
        final int overlayBgColor;
        final int overlayTextColor;
        if (result.overallOk) {
          overlayText = "ULD " + containerId +
              "\n✓ Correct at " + rampPosition.asCode() +
              "\nTarget: " + expectedCode;
          overlayBgColor = 0xCC1B5E20; // semi-transparent green
          overlayTextColor = 0xFFFFFFFF; // white
        } else {
          StringBuilder msg = new StringBuilder();
          msg.append("ULD ").append(containerId).append("\n");
          msg.append("✗ Wrong: ").append(rampPosition.asCode());
          if (result.expectedPosition != null) {
            msg.append("\nExpected: ").append(result.expectedPosition.asCode());
          } else {
            msg.append("\nExpected: <none in plan>");
          }
          if (!result.withinWeightLimit) {
            msg.append("\n⚠ Overweight");
          }
          if (result.suggestedPosition != null) {
            msg.append("\n→ Try: ").append(result.suggestedPosition.asCode());
          }
          overlayText = msg.toString();
          overlayBgColor = 0xCCB71C1C; // semi-transparent red
          overlayTextColor = 0xFFFFFFFF; // white
        }

        final boolean showBanner = !result.overallOk;
        final String bannerText = showBanner
            ? "Warning: Mismatch detected – move ULD " + containerId
            : "";
        
        // Create overlay label at world position
        OverlayLabel containerLabel = new OverlayLabel(
            hitPose, overlayText, overlayBgColor, overlayTextColor);
        overlayLabels.add(containerLabel);
        
        // Update bottom info box and top banner
        runOnUiThread(
            () -> {
              if (placementInfoBox != null) {
                placementInfoBox.setText(overlayText);
                placementInfoBox.setBackgroundColor(overlayBgColor);
              }
              if (globalWarningBanner != null) {
                if (showBanner) {
                  globalWarningBanner.setText(bannerText);
                  globalWarningBanner.setVisibility(View.VISIBLE);
                } else {
                  globalWarningBanner.setVisibility(View.GONE);
                }
              }
            });
        
        // Move to next demo target for the following tap.
        advanceDemoTarget();

        // 6) Visualize suggested better placement if available.
        if (suggestionAnchor != null) {
          suggestionAnchor.detach();
          suggestionAnchor = null;
        }
        if (result.suggestedPosition != null) {
          // Convert suggested logical position back into a world pose using coordinate converter.
          Pose suggestedWorldPose = coordinateConverter.rampPositionToWorldPose(result.suggestedPosition);
          suggestionAnchor = session.createAnchor(suggestedWorldPose);

          // 2D label at suggested position ("Place ULD here")
          String suggestionText = "Place ULD " + containerId + " here → "
              + result.suggestedPosition.asCode();
          OverlayLabel suggestionLabel = new OverlayLabel(
              suggestedWorldPose,
              suggestionText,
              0xCCFFFFFF, // light card
              0xFF000000  // dark text
          );
          overlayLabels.add(suggestionLabel);
        }

        // Only consider closest hit
        break;
      }
    }
  }


  /** Checks if we detected at least one plane. */
  private boolean hasTrackingPlane() {
    for (Plane plane : session.getAllTrackables(Plane.class)) {
      if (plane.getTrackingState() == TrackingState.TRACKING) {
        return true;
      }
    }
    return false;
  }

  /** Ensures that rampAnchor is set on first tap at a tracking plane. */
  private void ensureRampAnchor() {
    // Don't do anything here - we'll create the anchor on first tap instead
  }

  /** Create ramp anchor at the first tap position. */
  private boolean createRampAnchorFromTap(HitResult hit, Plane plane) {
    if (rampAnchor != null) {
      return true; // Already created
    }

    // Create anchor at the tap position (this becomes top-left corner = 1L)
    rampAnchor = hit.createAnchor();

    // Use FIXED grid size instead of plane dimensions
    // This ensures consistent positioning regardless of detected plane size
    // Grid: 0.3m wide (0.15m per side), 0.4m long total
    float gridWidthMeters = 0.3f;   // Total width for L+R sides
    float gridLengthMeters = 0.4f;  // Total length for all rows

    coordinateConverter = new RampCoordinateConverter(
        rampAnchor,
        gridWidthMeters,
        gridLengthMeters,
        aircraftConfig.getTotalRows()
    );

    Pose anchorPose = rampAnchor.getPose();
    float[] anchorTx = anchorPose.getTranslation();
    Log.i(TAG, String.format(
        "Ramp anchor created at first tap (top-left corner): (%.3f, %.3f, %.3f) grid=%.2fm×%.2fm rows=%d",
        anchorTx[0], anchorTx[1], anchorTx[2],
        gridWidthMeters, gridLengthMeters, aircraftConfig.getTotalRows()));

    return true;
  }


  /** Configures the session with feature settings. */
  private void configureSession() {
    Config config = session.getConfig();
    // Simple configuration: camera image only, no depth or instant placement.
    config.setLightEstimationMode(Config.LightEstimationMode.DISABLED);
    config.setDepthMode(Config.DepthMode.DISABLED);
    config.setInstantPlacementMode(InstantPlacementMode.DISABLED);
    session.configure(config);
  }
}
