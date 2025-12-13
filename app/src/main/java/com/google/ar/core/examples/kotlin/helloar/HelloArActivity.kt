package com.google.ar.core.examples.kotlin.helloar

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.*
import com.google.ar.core.Config
import com.google.ar.core.Config.InstantPlacementMode
import com.google.ar.core.Session
import com.google.ar.core.examples.java.common.helpers.CameraPermissionHelper
import com.google.ar.core.examples.java.common.helpers.DepthSettings
import com.google.ar.core.examples.java.common.helpers.FullScreenHelper
import com.google.ar.core.examples.java.common.helpers.InstantPlacementSettings
import com.google.ar.core.examples.java.common.samplerender.SampleRender
import com.google.ar.core.examples.kotlin.common.helpers.ARCoreSessionLifecycleHelper
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException

class HelloArActivity : AppCompatActivity(), SensorEventListener {
  companion object {
    private const val TAG = "HelloArActivity"
    private const val LOCATION_PERMISSION_REQUEST = 1001
  }

  // AR session lifecycle helper (name must match usages in renderer)
  lateinit var arCoreSessionHelper: ARCoreSessionLifecycleHelper
  lateinit var view: HelloArView
  lateinit var renderer: HelloArRenderer

  val instantPlacementSettings = InstantPlacementSettings()
  val depthSettings = DepthSettings()

  // Location + sensors
  private lateinit var fusedLocationClient: FusedLocationProviderClient
  private lateinit var locationCallback: LocationCallback
  private lateinit var sensorManager: SensorManager
  private var rotationVectorSensor: Sensor? = null

  // Latest device position/heading (kept here and passed to renderer)
  @Volatile var deviceLat: Double = Double.NaN
  @Volatile var deviceLon: Double = Double.NaN
  @Volatile var deviceHeadingRad: Double = 0.0

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    // Setup ARCore session lifecycle helper and configuration.
    arCoreSessionHelper = ARCoreSessionLifecycleHelper(this)

    // Exceptions -> show message using view (view not initialized yet; callback will only be used after view exists)
    arCoreSessionHelper.exceptionCallback = { exception ->
      val message =
        when (exception) {
          is UnavailableUserDeclinedInstallationException ->
            "Please install Google Play Services for AR"
          is UnavailableApkTooOldException -> "Please update ARCore"
          is UnavailableSdkTooOldException -> "Please update this app"
          is UnavailableDeviceNotCompatibleException -> "This device does not support AR"
          is CameraNotAvailableException -> "Camera not available. Try restarting the app."
          else -> "Failed to create AR session: $exception"
        }
      Log.e(TAG, "ARCore threw an exception", exception)
      runOnUiThread {
        if (::view.isInitialized) view.snackbarHelper.showError(this, message)
      }
    }

    arCoreSessionHelper.beforeSessionResume = ::configureSession
    lifecycle.addObserver(arCoreSessionHelper)

    // Set up the Hello AR renderer.
    renderer = HelloArRenderer(this)
    lifecycle.addObserver(renderer)

    // Set up Hello AR UI.
    view = HelloArView(this)
    lifecycle.addObserver(view)
    setContentView(view.root)

    // Location & sensor init
    fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
    sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
    rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    // Location callback to receive updates
    locationCallback = object : LocationCallback() {
      override fun onLocationResult(result: LocationResult) {
        val loc = result.lastLocation ?: return
        deviceLat = loc.latitude
        deviceLon = loc.longitude
        // pass to renderer
        renderer.setDeviceLocation(deviceLat, deviceLon)
      }
    }

    // Hook up UI: read lat/lon from edit texts and pass to renderer
    val editLat = findViewById<EditText>(R.id.editLat)
    val editLon = findViewById<EditText>(R.id.editLon)
    val btnSubmit = findViewById<Button>(R.id.btnSubmitCoords)

    btnSubmit.setOnClickListener {
      val lat = editLat.text.toString().toDoubleOrNull()
      val lon = editLon.text.toString().toDoubleOrNull()
      if (lat == null || lon == null) {
        Toast.makeText(this, "Enter valid latitude and longitude", Toast.LENGTH_SHORT).show()
        return@setOnClickListener
      }
      renderer.setTargetLatLon(lat, lon)
      Toast.makeText(this, "Target lat/lon set: $lat, $lon", Toast.LENGTH_SHORT).show()
    }

    // Sets up an example renderer using our HelloARRenderer.
    SampleRender(view.surfaceView, renderer, assets)

    depthSettings.onCreate(this)
    instantPlacementSettings.onCreate(this)

    // Ask for location permission if needed
    if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
      ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), LOCATION_PERMISSION_REQUEST)
    } else {
      startLocationUpdates()
    }
  }

  private fun startLocationUpdates() {
    val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L).build()
    if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
      fusedLocationClient.requestLocationUpdates(req, locationCallback, mainLooper)
      fusedLocationClient.lastLocation.addOnSuccessListener { loc: Location? ->
        loc?.let {
          deviceLat = it.latitude
          deviceLon = it.longitude
          renderer.setDeviceLocation(deviceLat, deviceLon)
        }
      }
    }
  }

  override fun onResume() {
    super.onResume()
    rotationVectorSensor?.also { sensor ->
      sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
    }
  }

  override fun onPause() {
    super.onPause()
    sensorManager.unregisterListener(this)
  }

  override fun onDestroy() {
    super.onDestroy()
    fusedLocationClient.removeLocationUpdates(locationCallback)
  }

  // SensorEventListener
  override fun onSensorChanged(event: SensorEvent?) {
    if (event == null) return
    if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
      val rotationMatrix = FloatArray(9)
      SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
      // get orientation
      val orientations = FloatArray(3)
      SensorManager.getOrientation(rotationMatrix, orientations)
      // orientations[0] is azimuth (radians) from device coordinate system
      deviceHeadingRad = orientations[0].toDouble()
      renderer.setDeviceHeading(deviceHeadingRad)
    }
  }

  override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
    // no-op
  }

  // Session config
  fun configureSession(session: Session) {
    session.configure(
      session.config.apply {
        lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR

        depthMode =
          if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
            Config.DepthMode.AUTOMATIC
          } else {
            Config.DepthMode.DISABLED
          }

        instantPlacementMode =
          if (instantPlacementSettings.isInstantPlacementEnabled) {
            InstantPlacementMode.LOCAL_Y_UP
          } else {
            InstantPlacementMode.DISABLED
          }
      }
    )
  }

  override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    if (requestCode == LOCATION_PERMISSION_REQUEST) {
      if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
        startLocationUpdates()
      } else {
        Toast.makeText(this, "Location permission is required for latitude/longitude placement", Toast.LENGTH_LONG).show()
      }
    }

    // Camera permission handling (original)
    if (!CameraPermissionHelper.hasCameraPermission(this)) {
      Toast.makeText(this, "Camera permission is needed to run this application", Toast.LENGTH_LONG).show()
      if (!CameraPermissionHelper.shouldShowRequestPermissionRationale(this)) {
        CameraPermissionHelper.launchPermissionSettings(this)
      }
      finish()
    }
  }

  override fun onWindowFocusChanged(hasFocus: Boolean) {
    super.onWindowFocusChanged(hasFocus)
    FullScreenHelper.setFullScreenOnWindowFocusChanged(this, hasFocus)
  }
}
