package com.google.ar.core.examples.kotlin.helloar

import android.opengl.GLES30
import android.opengl.Matrix
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.ar.core.Anchor
import com.google.ar.core.Camera
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.InstantPlacementPoint
import com.google.ar.core.LightEstimate
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Session
import com.google.ar.core.Trackable
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.google.ar.core.examples.java.common.helpers.DisplayRotationHelper
import com.google.ar.core.examples.java.common.helpers.TrackingStateHelper
import com.google.ar.core.examples.java.common.samplerender.Framebuffer
import com.google.ar.core.examples.java.common.samplerender.GLError
import com.google.ar.core.examples.java.common.samplerender.Mesh
import com.google.ar.core.examples.java.common.samplerender.SampleRender
import com.google.ar.core.examples.java.common.samplerender.Shader
import com.google.ar.core.examples.java.common.samplerender.Texture
import com.google.ar.core.examples.java.common.samplerender.VertexBuffer
import com.google.ar.core.examples.java.common.samplerender.arcore.BackgroundRenderer
import com.google.ar.core.examples.java.common.samplerender.arcore.PlaneRenderer
import com.google.ar.core.examples.java.common.samplerender.arcore.SpecularCubemapFilter
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.NotYetAvailableException
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

class HelloArRenderer(val activity: HelloArActivity) : SampleRender.Renderer, DefaultLifecycleObserver {
  companion object {
    private const val TAG = "HelloArRenderer"
    private val Z_NEAR = 0.1f
    private val Z_FAR = 100f
    val APPROXIMATE_DISTANCE_METERS = 2.0f
    val CUBEMAP_RESOLUTION = 16
    val CUBEMAP_NUMBER_OF_IMPORTANCE_SAMPLES = 32
    private val sphericalHarmonicFactors = floatArrayOf(
      0.282095f, -0.325735f, 0.325735f, -0.325735f, 0.273137f, -0.273137f, 0.078848f, -0.273137f, 0.136569f
    )
  }

  lateinit var render: SampleRender
  lateinit var planeRenderer: PlaneRenderer
  lateinit var backgroundRenderer: BackgroundRenderer
  lateinit var virtualSceneFramebuffer: Framebuffer
  var hasSetTextureNames = false

  lateinit var pointCloudVertexBuffer: VertexBuffer
  lateinit var pointCloudMesh: Mesh
  lateinit var pointCloudShader: Shader
  var lastPointCloudTimestamp: Long = 0

  lateinit var virtualObjectMesh: Mesh
  lateinit var virtualObjectShader: Shader
  lateinit var virtualObjectAlbedoTexture: Texture
  lateinit var virtualObjectAlbedoInstantPlacementTexture: Texture

  private val wrappedAnchors = mutableListOf<WrappedAnchor>()

  lateinit var dfgTexture: Texture
  lateinit var cubemapFilter: SpecularCubemapFilter

  val modelMatrix = FloatArray(16)
  val viewMatrix = FloatArray(16)
  val projectionMatrix = FloatArray(16)
  val modelViewMatrix = FloatArray(16)
  val modelViewProjectionMatrix = FloatArray(16)
  val sphericalHarmonicsCoefficients = FloatArray(9 * 3)
  val viewInverseMatrix = FloatArray(16)
  val worldLightDirection = floatArrayOf(0f, 0f, 0f, 0f)
  val viewLightDirection = FloatArray(4)

  private val mainLightIntensity = FloatArray(3) { 1.0f }

  // device & target lat/lon + heading
  @Volatile private var deviceLat: Double = Double.NaN
  @Volatile private var deviceLon: Double = Double.NaN
  @Volatile private var deviceHeadingRad: Double = 0.0
  @Volatile private var targetLat: Double = Double.NaN
  @Volatile private var targetLon: Double = Double.NaN

  // region visualization (not used for lat/lon -- optional)
  private var showRegion = false
  private val regionCenter = floatArrayOf(0f, 0f, 0f)
  private var regionSizeX = 0f
  private var regionSizeZ = 0f
  private var lastPlaneY = 0f

  fun setDeviceLocation(lat: Double, lon: Double) {
    deviceLat = lat
    deviceLon = lon
  }

  fun setDeviceHeading(azimuthRad: Double) {
    deviceHeadingRad = azimuthRad
  }

  fun setTargetLatLon(lat: Double, lon: Double) {
    targetLat = lat
    targetLon = lon
    Log.i(TAG, "Target lat/lon updated: $lat, $lon")
  }

  fun setAllowedRegionBounds(x1: Float, z1: Float, x2: Float, z2: Float) {
    regionCenter[0] = (x1 + x2) / 2f
    regionCenter[1] = lastPlaneY
    regionCenter[2] = (z1 + z2) / 2f
    regionSizeX = abs(x2 - x1)
    regionSizeZ = abs(z2 - z1)
    showRegion = true
  }

  val session: Session?
    get() = activity.arCoreSessionHelper.session

  val displayRotationHelper = DisplayRotationHelper(activity)
  val trackingStateHelper = TrackingStateHelper(activity)

  override fun onResume(owner: LifecycleOwner) {
    displayRotationHelper.onResume()
    hasSetTextureNames = false
  }

  override fun onPause(owner: LifecycleOwner) {
    displayRotationHelper.onPause()
  }

  override fun onSurfaceCreated(render: SampleRender) {
    this.render = render
    try {
      planeRenderer = PlaneRenderer(render)
      backgroundRenderer = BackgroundRenderer(render)
      virtualSceneFramebuffer = Framebuffer(render, 1, 1)

      cubemapFilter = SpecularCubemapFilter(render, CUBEMAP_RESOLUTION, CUBEMAP_NUMBER_OF_IMPORTANCE_SAMPLES)
      dfgTexture = Texture(render, Texture.Target.TEXTURE_2D, Texture.WrapMode.CLAMP_TO_EDGE, false)

      // load dfg.raw
      val dfgResolution = 64
      val dfgChannels = 2
      val halfFloatSize = 2
      val buffer: ByteBuffer = ByteBuffer.allocateDirect(dfgResolution * dfgResolution * dfgChannels * halfFloatSize)
      activity.assets.open("models/dfg.raw").use { it.read(buffer.array()) }

      GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, dfgTexture.textureId)
      GLError.maybeThrowGLException("Failed to bind DFG texture", "glBindTexture")

      GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RG16F, dfgResolution, dfgResolution, 0, GLES30.GL_RG, GLES30.GL_HALF_FLOAT, buffer)
      GLError.maybeThrowGLException("Failed to populate DFG texture", "glTexImage2D")

      pointCloudShader = Shader.createFromAssets(render, "shaders/point_cloud.vert", "shaders/point_cloud.frag", null)
        .setVec4("u_Color", floatArrayOf(31f / 255f, 188f / 255f, 210f / 255f, 1f))
        .setFloat("u_PointSize", 5.0f)

      pointCloudVertexBuffer = VertexBuffer(render, 4, null)
      pointCloudMesh = Mesh(render, Mesh.PrimitiveMode.POINTS, null, arrayOf(pointCloudVertexBuffer))

      virtualObjectAlbedoTexture = Texture.createFromAsset(render, "models/box_texture.png", Texture.WrapMode.CLAMP_TO_EDGE, Texture.ColorFormat.LINEAR)
      virtualObjectAlbedoInstantPlacementTexture = Texture.createFromAsset(render, "models/box_texture.png", Texture.WrapMode.CLAMP_TO_EDGE, Texture.ColorFormat.SRGB)
      val virtualObjectPbrTexture = Texture.createFromAsset(render, "models/box_texture.png", Texture.WrapMode.CLAMP_TO_EDGE, Texture.ColorFormat.LINEAR)

      virtualObjectMesh = Mesh.createFromAsset(render, "models/box.obj")
      virtualObjectShader = Shader.createFromAssets(render, "shaders/environmental_hdr.vert", "shaders/environmental_hdr.frag", mapOf("NUMBER_OF_MIPMAP_LEVELS" to cubemapFilter.numberOfMipmapLevels.toString()))
        .setTexture("u_AlbedoTexture", virtualObjectAlbedoTexture)
        .setTexture("u_RoughnessMetallicAmbientOcclusionTexture", virtualObjectPbrTexture)
        .setTexture("u_Cubemap", cubemapFilter.filteredCubemapTexture)
        .setTexture("u_DfgTexture", dfgTexture)
    } catch (e: IOException) {
      Log.e(TAG, "Failed to read a required asset file", e)
      showError("Failed to read a required asset file: $e")
    }
  }

  override fun onSurfaceChanged(render: SampleRender, width: Int, height: Int) {
    displayRotationHelper.onSurfaceChanged(width, height)
    virtualSceneFramebuffer.resize(width, height)
  }

  override fun onDrawFrame(render: SampleRender) {
    val session = session ?: return

    if (!hasSetTextureNames) {
      // older API uses single texture name
      session.setCameraTextureName(backgroundRenderer.cameraColorTexture.textureId)
      hasSetTextureNames = true
    }

    displayRotationHelper.updateSessionIfNeeded(session)

    val frame = try {
      session.update()
    } catch (e: CameraNotAvailableException) {
      Log.e(TAG, "Camera not available during onDrawFrame", e)
      showError("Camera not available. Try restarting the app.")
      return
    }

    val camera = frame.camera

    try {
      backgroundRenderer.setUseDepthVisualization(render, activity.depthSettings.depthColorVisualizationEnabled())
      backgroundRenderer.setUseOcclusion(render, activity.depthSettings.useDepthForOcclusion())
    } catch (e: IOException) {
      Log.e(TAG, "Failed to read a required asset file", e)
      showError("Failed to read a required asset file: $e")
      return
    }

    backgroundRenderer.updateDisplayGeometry(frame)
    val shouldGetDepthImage = activity.depthSettings.useDepthForOcclusion() || activity.depthSettings.depthColorVisualizationEnabled()
    if (camera.trackingState == TrackingState.TRACKING && shouldGetDepthImage) {
      try {
        val depthImage = frame.acquireDepthImage16Bits()
        backgroundRenderer.updateCameraDepthTexture(depthImage)
        depthImage.close()
      } catch (e: NotYetAvailableException) {
        // depth not ready yet -- fine
      }
    }

    handleTap(frame, camera)

    trackingStateHelper.updateKeepScreenOnFlag(camera.trackingState)

    val message: String? = when {
      camera.trackingState == TrackingState.PAUSED && camera.trackingFailureReason == TrackingFailureReason.NONE ->
        activity.getString(R.string.searching_planes)
      camera.trackingState == TrackingState.PAUSED ->
        TrackingStateHelper.getTrackingFailureReasonString(camera)
      session.hasTrackingPlane() && wrappedAnchors.isEmpty() ->
        activity.getString(R.string.waiting_taps)
      session.hasTrackingPlane() && wrappedAnchors.isNotEmpty() -> null
      else -> activity.getString(R.string.searching_planes)
    }
    if (message == null) {
      activity.view.snackbarHelper.hide(activity)
    } else {
      activity.view.snackbarHelper.showMessage(activity, message)
    }

    if (frame.timestamp != 0L) {
      backgroundRenderer.drawBackground(render)
    }

    if (camera.trackingState == TrackingState.PAUSED) return

    camera.getProjectionMatrix(projectionMatrix, 0, Z_NEAR, Z_FAR)
    camera.getViewMatrix(viewMatrix, 0)

    frame.acquirePointCloud().use { pointCloud ->
      if (pointCloud.timestamp > lastPointCloudTimestamp) {
        pointCloudVertexBuffer.set(pointCloud.points)
        lastPointCloudTimestamp = pointCloud.timestamp
      }
      Matrix.multiplyMM(modelViewProjectionMatrix, 0, projectionMatrix, 0, viewMatrix, 0)
      pointCloudShader.setMat4("u_ModelViewProjection", modelViewProjectionMatrix)
      render.draw(pointCloudMesh, pointCloudShader)
    }

    planeRenderer.drawPlanes(render, session.getAllTrackables(Plane::class.java), camera.displayOrientedPose, projectionMatrix)
    updateLightEstimation(frame.lightEstimate, viewMatrix)

    // draw scene: (we draw anchors, tinted if invalid)
    render.clear(virtualSceneFramebuffer, 0f, 0f, 0f, 0f)

    for ((anchor, trackable, isValid) in wrappedAnchors.filter { it.anchor.trackingState == TrackingState.TRACKING }) {
      anchor.pose.toMatrix(modelMatrix, 0)

      // scale down model
      val scaleFactor = 0.6f
      Matrix.scaleM(modelMatrix, 0, scaleFactor, scaleFactor, scaleFactor)

      Matrix.multiplyMM(modelViewMatrix, 0, viewMatrix, 0, modelMatrix, 0)
      Matrix.multiplyMM(modelViewProjectionMatrix, 0, projectionMatrix, 0, modelViewMatrix, 0)

      virtualObjectShader.setMat4("u_ModelView", modelViewMatrix)
      virtualObjectShader.setMat4("u_ModelViewProjection", modelViewProjectionMatrix)

      val texture = if ((trackable as? InstantPlacementPoint)?.trackingMethod == InstantPlacementPoint.TrackingMethod.SCREENSPACE_WITH_APPROXIMATE_DISTANCE)
        virtualObjectAlbedoInstantPlacementTexture else virtualObjectAlbedoTexture
      virtualObjectShader.setTexture("u_AlbedoTexture", texture)

      if (isValid) {
        virtualObjectShader.setVec3("u_LightIntensity", mainLightIntensity)
      } else {
        virtualObjectShader.setVec3("u_LightIntensity", floatArrayOf(3.0f, 0.1f, 0.1f))
      }

      render.draw(virtualObjectMesh, virtualObjectShader, virtualSceneFramebuffer)
    }

    backgroundRenderer.drawVirtualScene(render, virtualSceneFramebuffer, Z_NEAR, Z_FAR)
  }

  private fun handleTap(frame: Frame, camera: Camera) {
    if (camera.trackingState != TrackingState.TRACKING) return
    val tap = activity.view.tapHelper.poll() ?: return

    val hitResultList = if (activity.instantPlacementSettings.isInstantPlacementEnabled) {
      frame.hitTestInstantPlacement(tap.x, tap.y, APPROXIMATE_DISTANCE_METERS)
    } else {
      frame.hitTest(tap)
    }

    val firstHitResult = hitResultList.firstOrNull { hit ->
      when (val trackable = hit.trackable!!) {
        is Plane ->
          trackable.isPoseInPolygon(hit.hitPose) && PlaneRenderer.calculateDistanceToPlane(hit.hitPose, camera.pose) > 0
        is Point -> trackable.orientationMode == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL
        is InstantPlacementPoint -> true
        is DepthPoint -> true
        else -> false
      }
    }

    if (firstHitResult != null) {
      if (wrappedAnchors.size >= 20) {
        wrappedAnchors[0].anchor.detach()
        wrappedAnchors.removeAt(0)
      }

      val pose = firstHitResult.hitPose
      val tx = pose.tx()
      val tz = pose.tz()
      lastPlaneY = pose.ty()

      // Convert tap to lat/lon using device location + heading
      val latlon = convertPoseToLatLon(tx, tz)
      var isValidPlacement = false
      if (latlon != null && !targetLat.isNaN() && !targetLon.isNaN()) {
        val (latTap, lonTap) = latlon
        val dMeters = haversineDistanceMeters(latTap, lonTap, targetLat, targetLon)
        val toleranceMeters = 3.0 // change as desired
        isValidPlacement = dMeters <= toleranceMeters

        // debug toast: tap lat/lon and distance
        activity.runOnUiThread {
          Toast.makeText(activity, "Tap Lat: %.6f Lon: %.6f (%.1fm from target)".format(latTap, lonTap, dMeters), Toast.LENGTH_LONG).show()
        }
      } else {
        activity.runOnUiThread {
          Toast.makeText(activity, "Device location/target not available yet", Toast.LENGTH_SHORT).show()
        }
      }

      if (!isValidPlacement) {
        activity.runOnUiThread {
          Toast.makeText(activity, "This is not the correct place for your object placement", Toast.LENGTH_SHORT).show()
        }
      }

      wrappedAnchors.add(WrappedAnchor(firstHitResult.createAnchor(), firstHitResult.trackable, isValidPlacement))
      activity.runOnUiThread { activity.view.showOcclusionDialogIfNeeded() }
    }
  }

  private fun convertPoseToLatLon(tx: Float, tz: Float): Pair<Double, Double>? {
    if (deviceLat.isNaN() || deviceLon.isNaN()) return null

    // ARCore: tx = right (meters), tz = forward? (z-forward is negative in camera space for forward)
    val right = tx.toDouble()
    val forward = -tz.toDouble() // treat forward as -tz

    // deviceHeadingRad: azimuth in radians where 0 = North
    val theta = deviceHeadingRad

    // Convert camera-local right/forward to East/North components.
    // east = forward*sin(theta) + right*cos(theta)
    // north = forward*cos(theta) - right*sin(theta)
    val east = forward * sin(theta) + right * cos(theta)
    val north = forward * cos(theta) - right * sin(theta)

    val R = 6378137.0 // earth radius in meters
    val deltaLat = north / R
    val deltaLon = east / (R * cos(Math.toRadians(deviceLat)))

    val latTap = deviceLat + Math.toDegrees(deltaLat)
    val lonTap = deviceLon + Math.toDegrees(deltaLon)
    return Pair(latTap, lonTap)
  }

  private fun haversineDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val R = 6371000.0 // meters
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
    val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    return R * c
  }

  private fun updateLightEstimation(lightEstimate: LightEstimate, viewMatrix: FloatArray) {
    if (lightEstimate.state != LightEstimate.State.VALID) {
      virtualObjectShader.setBool("u_LightEstimateIsValid", false)
      return
    }
    virtualObjectShader.setBool("u_LightEstimateIsValid", true)
    Matrix.invertM(viewInverseMatrix, 0, viewMatrix, 0)
    virtualObjectShader.setMat4("u_ViewInverse", viewInverseMatrix)
    updateMainLight(lightEstimate.environmentalHdrMainLightDirection, lightEstimate.environmentalHdrMainLightIntensity, viewMatrix)
    updateSphericalHarmonicsCoefficients(lightEstimate.environmentalHdrAmbientSphericalHarmonics)
    cubemapFilter.update(lightEstimate.acquireEnvironmentalHdrCubeMap())
  }

  private fun updateMainLight(direction: FloatArray, intensity: FloatArray, viewMatrix: FloatArray) {
    worldLightDirection[0] = direction[0]
    worldLightDirection[1] = direction[1]
    worldLightDirection[2] = direction[2]
    Matrix.multiplyMV(viewLightDirection, 0, viewMatrix, 0, worldLightDirection, 0)
    virtualObjectShader.setVec4("u_ViewLightDirection", viewLightDirection)

    mainLightIntensity[0] = intensity[0]
    mainLightIntensity[1] = intensity[1]
    mainLightIntensity[2] = intensity[2]
    virtualObjectShader.setVec3("u_LightIntensity", intensity)
  }

  private fun updateSphericalHarmonicsCoefficients(coefficients: FloatArray) {
    require(coefficients.size == 9 * 3) { "The given coefficients array must be of length 27" }
    for (i in 0 until 9 * 3) {
      sphericalHarmonicsCoefficients[i] = coefficients[i] * sphericalHarmonicFactors[i / 3]
    }
    virtualObjectShader.setVec3Array("u_SphericalHarmonicsCoefficients", sphericalHarmonicsCoefficients)
  }

  private fun showError(errorMessage: String) = activity.view.snackbarHelper.showError(activity, errorMessage)
}

/** Checks if we detected at least one plane. */
private fun Session.hasTrackingPlane(): Boolean =
  getAllTrackables(Plane::class.java).any { it.trackingState == TrackingState.TRACKING }

private data class WrappedAnchor(val anchor: Anchor, val trackable: Trackable, val isValid: Boolean)
