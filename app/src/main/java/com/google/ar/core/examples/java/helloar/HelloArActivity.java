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
import com.google.ar.core.exceptions.UnavailableApkTooOldException;
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException;
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException;
import com.google.ar.core.exceptions.UnavailableSdkTooOldException;
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


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
  // Lock to protect overlayLabels from concurrent modification (GL thread vs UI thread).
  private final Object overlayLock = new Object();
  private android.widget.RelativeLayout overlayContainer;
  // Pool of reusable TextViews for overlay labels to reduce allocations.
  private final List<TextView> overlayViewPool = new ArrayList<>();

  // Grid visualization - grey boxes for each cell
  // Grid data: each GridCellAnchor holds the ARCore anchor, logical position,
  // and a direct reference to its overlay label used for UI updates.
  private final List<GridCellAnchor> gridCellAnchors = new ArrayList<>();
  // Fast lookup by RampPosition for O(1) access
  private final Map<RampPosition, GridCellAnchor> gridCellMap = new HashMap<>();
  // Track which RampPosition a containerId is currently placed in
  private final Map<String, RampPosition> containerPlacement = new HashMap<>();

  // (placedContainers removed — gridCellAnchors holds placement state)

  private static class GridCellAnchor {
    final Anchor anchor;
    final RampPosition position;
    final GridCell cell;
    OverlayLabel slotLabel;
    String containerId;
    boolean isCorrect;

    GridCellAnchor(Anchor anchor, RampPosition position, GridCell cell) {
      this.anchor = anchor;
      this.position = position;
      this.cell = cell;
      this.containerId = null;
      this.isCorrect = false;
    }
  }

  private static final float Z_NEAR = 0.1f;
  private static final float Z_FAR = 100f;
  // Single source of truth for row spacing (meters between rows)
  private static final float ROW_SPACING_METERS = 0.25f;

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
    Log.d("HELLO_AR", "HelloArActivity started");
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

    // Ensure texture names are (re-)set on the GL thread after resume.
    hasSetTextureNames = false;

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
    // Set up the camera background and grid rendering.
    try {
      backgroundRenderer = new BackgroundRenderer(render);
      backgroundRenderer.setUseDepthVisualization(render, /*useDepthVisualization=*/ false);
      
        // Grid visualization uses 2D overlay labels now; no 3D mesh or shader required.
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

    // Populate the (static) loadingPlan with demo targets so evaluation remains deterministic.
    if (loadingPlan != null) {
      for (DemoTarget dt : demoTargets) {
        loadingPlan.putEntry(new LoadingPlan.PlanEntry(dt.containerId, dt.weightKg, dt.positionCode));
      }
    }
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

    // Ramp anchor creation is handled in handleTap(); only create grid when tracking.
    if (camera.getTrackingState() == TrackingState.TRACKING) {
      createGridIfNeeded();
    }

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
      boolean overlaysEmpty;
      synchronized (overlayLock) {
        overlaysEmpty = overlayLabels.isEmpty();
      }
      if (overlaysEmpty && gridCellAnchors.isEmpty()) {
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

    // --- Draw camera background ---
    if (backgroundRenderer != null) {
      GLES30.glDisable(GLES30.GL_DEPTH_TEST);
      GLES30.glDepthMask(false);
      GLES30.glDisable(GLES30.GL_BLEND);

      backgroundRenderer.drawBackground(render);

      GLES30.glEnable(GLES30.GL_BLEND);
      GLES30.glEnable(GLES30.GL_DEPTH_TEST);
      GLES30.glDepthMask(true);
    }

    // If not tracking, do not attempt to project labels or render grid.
    if (camera.getTrackingState() == TrackingState.PAUSED) {
      return;
    }

    // Compute current projection + view matrices for rendering.
    camera.getProjectionMatrix(projectionMatrix, 0, Z_NEAR, Z_FAR);
    camera.getViewMatrix(viewMatrix, 0);

    // Grid visuals are handled by 2D overlay labels; do not update UI here.

    // Update 2D overlay labels positions based on world-to-screen projection.
    updateOverlayLabels(frame, camera, projectionMatrix, viewMatrix);
  }
  
  /**
   * Projects world positions to screen coordinates and updates overlay label positions.
   */
  private void updateOverlayLabels(Frame frame, Camera camera, float[] projectionMatrix, float[] viewMatrix) {
    if (overlayContainer == null) {
      return;
    }

    // Snapshot the overlay list under lock to avoid concurrent modification
    final List<OverlayLabel> snapshot;
    synchronized (overlayLock) {
      if (overlayLabels.isEmpty()) return;
      snapshot = new ArrayList<>(overlayLabels);
    }

    Log.d(TAG, "updateOverlayLabels: snapshotSize=" + snapshot.size() + " poolSize=" + overlayViewPool.size());

    int[] viewport = new int[4];
    GLES30.glGetIntegerv(GLES30.GL_VIEWPORT, viewport, 0);
    int screenWidth = viewport[2];
    int screenHeight = viewport[3];

    float[] mvpMatrix = new float[16];
    Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, viewMatrix, 0);

    runOnUiThread(() -> {
      for (OverlayLabel label : snapshot) {
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

        // Get or create (reused from pool) TextView for this label.
        TextView labelView = label.getView();
        if (labelView == null) {
          // Try to reuse a view from the pool first.
          if (!overlayViewPool.isEmpty()) {
            int last = overlayViewPool.size() - 1;
            labelView = overlayViewPool.remove(last);
          } else {
            labelView = new TextView(this);
            labelView.setPadding(24, 16, 24, 16);
            labelView.setTextSize(13);
            labelView.setTypeface(null, android.graphics.Typeface.BOLD);
            labelView.setMaxLines(3);
          }
          overlayContainer.addView(labelView);
          // Initialize the view with label's text and background color once.
          label.setView(labelView);
          labelView.setTag(label.getType());
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

    // Grid rendering replaces old point-cloud, plane and virtual object visualization.
    // Nothing else to draw here from the old sample; return to caller.
    return;
  }

  /**
   * Remove non-persistent overlay labels (placement results and suggestions).
   * Keeps grid slot labels which are marked persistent.
   */
  // Removes old green/red banners IMMEDIATELY (keeps grey grid labels)
  private void clearDynamicOverlaysImmediate() {
    synchronized (overlayLock) {
      List<OverlayLabel> keep = new ArrayList<>();
      for (OverlayLabel label : overlayLabels) {
        if (label.isPersistent()) { // keep only grid labels
          keep.add(label);
        }
      }
      overlayLabels.clear();
      overlayLabels.addAll(keep);
    }
  }
  // Removes leftover TextViews from screen (UI thread)
  private void clearDynamicOverlayViews() {
    if (overlayContainer == null) return;

    runOnUiThread(() -> {
      for (int i = overlayContainer.getChildCount() - 1; i >= 0; i--) {
        View v = overlayContainer.getChildAt(i);
        Object tag = v.getTag();

        if (tag == OverlayLabel.Type.DYNAMIC) {
          overlayContainer.removeView(v);
        }
      }
    });
  }

  // Handle only one tap per frame, as taps are usually low frequency compared to frame rate.
  private void handleTap(Frame frame, Camera camera) {
    MotionEvent tap = tapHelper.poll();
    if (tap == null || camera.getTrackingState() != TrackingState.TRACKING) {
      return;
    }

    // Prevent accidental double-taps in the same frame: only handle ACTION_UP
    if (tap.getAction() != MotionEvent.ACTION_UP) return;
    clearDynamicOverlaysImmediate();
    clearDynamicOverlayViews();

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

        // 3) Get current container from loading plan (support up to 40 containers)
        DemoTarget target = getCurrentDemoTarget();
        if (target == null) {
          Log.w(TAG, "No demo targets configured; skipping evaluation.");
          continue;
        }
        String containerId = target.containerId;
        float containerWeightKg = target.weightKg;

        // Loading plan entries are populated once in initDemoTargets(); do not mutate here.

        // 4) Evaluate placement against plan & limits.
        PlacementEvaluator.Result result =
          placementEvaluator.evaluate(containerId, containerWeightKg, rampPosition);

        // 5) Update grid cell color/state based on result (records and updates UI).
        updateGridCellForContainer(containerId, rampPosition, result.overallOk);

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

        // 6) Create 2D overlay label at tap position
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

        OverlayLabel containerLabel = new OverlayLabel(
                hitPose, overlayText, overlayBgColor, overlayTextColor);
        containerLabel.setPersistent(false);
        containerLabel.setType(OverlayLabel.Type.DYNAMIC);
        synchronized (overlayLock) {
          overlayLabels.add(containerLabel);
          Log.d(TAG, "Added container overlay for " + containerId + " at " + rampPosition.asCode()
              + " totalOverlays=" + overlayLabels.size());
        }
        
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

        // 7) Visualize suggested better placement if available.
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
          suggestionLabel.setType(OverlayLabel.Type.DYNAMIC);
          suggestionLabel.setPersistent(false);
          synchronized (overlayLock) {
            overlayLabels.add(suggestionLabel);
            Log.d(TAG, "Added suggestion overlay for " + containerId + " -> "
                + result.suggestedPosition.asCode() + " totalOverlays=" + overlayLabels.size());
          }
        }

        // Only consider closest hit
        break;
      }
    }
  }


  // Depth and instant-placement UI methods removed — not used by grid demo.

  /** Checks if we detected at least one plane. */
  private boolean hasTrackingPlane() {
    for (Plane plane : session.getAllTrackables(Plane.class)) {
      if (plane.getTrackingState() == TrackingState.TRACKING) {
        return true;
      }
    }
    return false;
  }

  /** Minimal session configuration used by this simplified demo. */
  private void configureSession() {
    if (session == null) return;

    Config config = new Config(session);
    config.setPlaneFindingMode(Config.PlaneFindingMode.HORIZONTAL);

    try {
      config.setDepthMode(Config.DepthMode.DISABLED);
    } catch (Exception e) {
      // ignore if depth mode not available
    }
    try {
      config.setLightEstimationMode(Config.LightEstimationMode.DISABLED);
    } catch (Exception e) {
      // ignore if not available on this device
    }

    session.configure(config);
  }

  

  /** Create ramp anchor at the first tap position. */
  private boolean createRampAnchorFromTap(HitResult hit, Plane plane) {
    if (rampAnchor != null) {
      return true; // Already created
    }

    // Create anchor at the tap position (this becomes top-left corner = 1L)
    // Use the plane-aligned hit pose so the anchor matches the real-world plane
    // orientation. Do not discard the pose rotation — that preserves correct
    // tap-to-slot mapping when the plane is tilted or rotated.
    rampAnchor = hit.createAnchor();

    // Use aircraft-dependent grid size instead of fixed dimensions
    int rows = aircraftConfig.getTotalRows();

    // Restore previous behavior: use small fixed width per side and
    // row spacing constant for length so grid aligns with plane surface
    // and does not appear floating in the air.
    float gridWidthMeters = 0.15f * 2;
    float gridLengthMeters = rows * ROW_SPACING_METERS;

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

    // Force grid creation immediately after ramp anchor
    createGridIfNeeded();

    return true;
  }

  /** Create grid cells if ramp anchor is established. */
  private void createGridIfNeeded() {
    if (rampAnchor == null || coordinateConverter == null || !gridCellAnchors.isEmpty()) {
      return;
    }

    int rows = aircraftConfig.getTotalRows();
    for (int row = 1; row <= rows; row++) {
      for (RampPosition.Side side : RampPosition.Side.values()) {
        RampPosition position = new RampPosition(row, side);
        Pose worldPose = coordinateConverter.rampPositionToWorldPose(position);
        Anchor cellAnchor = session.createAnchor(worldPose);
        GridCell cell = new GridCell(position, worldPose);
        GridCellAnchor gca = new GridCellAnchor(cellAnchor, position, cell);
        gridCellAnchors.add(gca);
        gridCellMap.put(position, gca);

        // Add a persistent grey overlay label for the grid cell so slot names are visible.
        OverlayLabel gridLabel = new OverlayLabel(
          worldPose,
          position.asCode(),
          0xCC616161, // grey background
          0xFFFFFFFF  // white text
        );
        gridLabel.setPersistent(true);
        gridLabel.setType(OverlayLabel.Type.GRID);
        synchronized (overlayLock) {
          overlayLabels.add(gridLabel);
          Log.d(TAG, "Added grid label " + position.asCode() + " totalOverlays=" + overlayLabels.size());
        }
        // Direct link from grid cell anchor to its overlay label for fast updates
        gca.slotLabel = gridLabel;
      }
    }
    Log.i(TAG, "Created " + gridCellAnchors.size() + " grid cells");
  }

  /** Create a simple unit cube mesh programmatically to avoid unpredictable OBJ scales. */
  // Unit cube and axis mesh creation removed — using 2D overlays for grid visualization.

  /** Update the visual state (2D overlays) for a given grid cell.
   *  This updates Android Views on the UI thread and should be called only
   *  when placement state changes (not every frame).
   */
  private void updateGridCellVisualState(RampPosition position) {
    if (position == null) return;
    final GridCellAnchor anchor = gridCellMap.get(position);
    if (anchor == null || anchor.slotLabel == null) return;

    final int bgColor = (anchor.containerId == null)
        ? 0xCC616161 // grey
        : (anchor.isCorrect ? 0xCC1B5E20 : 0xCCB71C1C);

    runOnUiThread(() -> {
      anchor.slotLabel.setBackgroundColor(bgColor);
      if (anchor.slotLabel.getView() != null) {
        anchor.slotLabel.getView().setBackgroundResource(
            bgColor == 0xCC616161
                ? R.drawable.bg_info_card
                : bgColor == 0xCC1B5E20
                    ? R.drawable.bg_label_ok
                    : R.drawable.bg_label_error);
      }
    });
  }

  /** Update grid cell when container is placed. */
  private void updateGridCellForContainer(
      String containerId,
      RampPosition actualPosition,
      boolean isCorrect
  ) {
    // Clear previous placement for this container (if any)
    RampPosition previous = containerPlacement.get(containerId);
    if (previous != null && !previous.equals(actualPosition)) {
      GridCellAnchor prevAnchor = gridCellMap.get(previous);
      if (prevAnchor != null) {
        prevAnchor.containerId = null;
        prevAnchor.isCorrect = false;
        prevAnchor.cell.setContainerId(null);
      }
    }

    // Update the target cell
    GridCellAnchor target = gridCellMap.get(actualPosition);
    if (target != null) {
      target.containerId = containerId;
      target.isCorrect = isCorrect;
      target.cell.setContainerId(containerId);
    }

    // Record placement
    containerPlacement.put(containerId, actualPosition);

    // Update visual state for affected cells (only on change).
    if (previous != null && !previous.equals(actualPosition)) {
      updateGridCellVisualState(previous);
    }
    updateGridCellVisualState(actualPosition);
  }
}
