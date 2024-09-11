package com.example.safewalkosm20

import android.os.PowerManager
import android.app.Activity
import android.database.Cursor
import android.provider.ContactsContract
import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import org.osmdroid.config.Configuration
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import android.telephony.SmsManager
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.LocationServices
import android.os.VibrationEffect
import android.os.Vibrator


@Suppress("NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS", "DEPRECATION")
class MainActivity : AppCompatActivity() {

    private val PICK_CONTACT_REQUEST = 1
    private var emergencyContact: String? = null
    private lateinit var map: MapView
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private val pathPoints = mutableListOf<GeoPoint>()
    private var isInitialLocationUpdate = true
    private var isZooming = false
    private var isExploring = false
    private var isDrawing = false
    private var isFeatureActive = false
    private lateinit var screenOffReceiver: ScreenOffReceiver
    private lateinit var startStopButton: Button
    private lateinit var sharedPreferences: SharedPreferences

    @SuppressLint("ClickableViewAccessibility", "MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, getSharedPreferences("osm_pref", MODE_PRIVATE))
        setContentView(R.layout.activity_main)

        sharedPreferences = getSharedPreferences("SafeWalkPrefs", Context.MODE_PRIVATE)
        emergencyContact = sharedPreferences.getString("emergencyContact", null)


        checkPermissions()

        map = findViewById(R.id.map)
        map.setMultiTouchControls(true)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                for (location in locationResult.locations) {
                    updateMapLocation(location)
                    if (isFeatureActive) {
                        checkDistanceFromPath(location)
                    }
                }
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
        } else {
            startLocationUpdates()
        }

        map.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isZooming = event.pointerCount > 1
                    isExploring = event.pointerCount == 1
                    false
                }
                MotionEvent.ACTION_UP -> {
                    if (!isZooming && isExploring && event.eventTime - event.downTime < ViewConfiguration.getTapTimeout()) {
                        val geoPoint = map.projection.fromPixels(event.x.toInt(), event.y.toInt()) as GeoPoint
                        pathPoints.add(geoPoint)
                        drawPath()
                        isDrawing = true
                    }
                    isZooming = false
                    isExploring = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount == 1) {
                        isExploring = true
                    } else if (event.pointerCount > 1) {
                        isZooming = true
                    }
                    false
                }
                else -> false
            }
        }

        val undoButton: Button = findViewById(R.id.undo_button)
        undoButton.setOnClickListener {
            if (pathPoints.isNotEmpty()) {
                pathPoints.removeAt(pathPoints.size - 1)
                drawPath()
            } else {
                Toast.makeText(this, "No points to undo", Toast.LENGTH_SHORT).show()
            }
        }

        val selectContactButton: Button = findViewById(R.id.select_contact_button)
        selectContactButton.setOnClickListener {
            val intent = Intent(Intent.ACTION_PICK, ContactsContract.Contacts.CONTENT_URI)
            startActivityForResult(intent, PICK_CONTACT_REQUEST)
        }

        val emergencyButton: Button = findViewById(R.id.emergency_button)
        emergencyButton.setOnClickListener {
            sendEmergencyAlert()
        }

        startStopButton = findViewById(R.id.start_stop_button)
        startStopButton.setOnClickListener {
            if (pathPoints.isEmpty()) {
                Toast.makeText(this, "No path drawn. Please draw a path first.", Toast.LENGTH_SHORT).show()
            } else {
                isFeatureActive = !isFeatureActive
                startStopButton.text = if (isFeatureActive) "STOP" else "START"
                if (isFeatureActive) {
                    startLocationUpdates()
                } else {
                    fusedLocationClient.removeLocationUpdates(locationCallback)
                }
            }
        }

        createNotificationChannel()


        screenOffReceiver = ScreenOffReceiver()
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        registerReceiver(screenOffReceiver, filter)
        Log.d("MainActivity", "ScreenOffReceiver registered")
    }


    @SuppressLint("SetTextI18n")
    private fun checkDistanceFromPath(location: Location) {
        if (pathPoints.isEmpty()) {
            showWarningNotification("No path drawn. Please draw a path first.")
            return
        }

        val userLocation = GeoPoint(location.latitude, location.longitude)
        val maxDistance = 30.0 // Maximum allowed distance in meters

        var isFarFromPath = true
        for (point in pathPoints) {
            try {
                val distance = userLocation.distanceToAsDouble(point)
                if (distance <= maxDistance) {
                    isFarFromPath = false
                    break
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Log.e("MainActivity", "Error calculating distance: ${e.message}")
            }
        }

        if (isFarFromPath) {
            isFeatureActive = false
            startStopButton.text = "START"
            Log.d("MainActivity", "User is too far from the path. Showing warning notification.")
            showWarningNotification("Too far from the Safe Path. Do you want to contact the emergency contact?")
        }
    }

    @SuppressLint("MissingPermission")
    private fun showWarningNotification(message: String) {
        Log.d("MainActivity", "Showing warning notification: $message")
        try {
            AlertDialog.Builder(this)
                .setTitle("Warning")
                .setMessage(message)
                .setPositiveButton("Yes") { _, _ ->
                    sendEmergencyAlert()
                }
                .setNegativeButton("No", null)
                .show()

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val notification = NotificationCompat.Builder(this, "safe_walk_channel")
                .setContentTitle("Warning")
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()
            notificationManager.notify(1, notification)

            triggerVibration()
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e("MainActivity", "Error showing notification: ${e.message}")
        }
    }

    private fun triggerVibration() {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            vibrator.vibrate(500)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Safe Walk Channel"
            val descriptionText = "Channel for Safe Walk notifications"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel("safe_walk_channel", name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
            Log.d("MainActivity", "Notification channel created")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_CONTACT_REQUEST && resultCode == Activity.RESULT_OK) {
            try {
                val contactUri = data?.data ?: return
                val cursor: Cursor? = contentResolver.query(contactUri, null, null, null, null)
                if (cursor != null && cursor.moveToFirst()) {
                    val id = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID))
                    val hasPhoneNumber = cursor.getInt(cursor.getColumnIndexOrThrow(ContactsContract.Contacts.HAS_PHONE_NUMBER)) > 0
                    if (hasPhoneNumber) {
                        val phones: Cursor? = contentResolver.query(
                            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                            null,
                            ContactsContract.CommonDataKinds.Phone.CONTACT_ID + " = ?",
                            arrayOf(id),
                            null
                        )
                        if (phones != null && phones.moveToFirst()) {
                            emergencyContact = phones.getString(phones.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER))
                            phones.close()
                            sharedPreferences.edit().putString("emergencyContact", emergencyContact).apply()
                        }
                    }
                    cursor.close()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this, "Failed to select contact", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun sendEmergencyAlert() {
        if (emergencyContact.isNullOrEmpty()) {
            Toast.makeText(this, "No emergency contact selected", Toast.LENGTH_SHORT).show()
            return
        }

        getCurrentLocation { currentLocation ->
            if (currentLocation == null) {
                Toast.makeText(this, "Failed to get current location", Toast.LENGTH_SHORT).show()
                return@getCurrentLocation
            }

            try {
                // Send SMS
                val smsManager = SmsManager.getDefault()
                val message = "Emergency! Current location: https://maps.google.com/?q=${currentLocation.latitude},${currentLocation.longitude}"
                smsManager.sendTextMessage(emergencyContact, null, message, null, null)

                // Make a call
                val callIntent = Intent(Intent.ACTION_CALL)
                callIntent.data = Uri.parse("tel:$emergencyContact")
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
                    startActivity(callIntent)
                } else {
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CALL_PHONE), 3)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this, "Failed to send emergency alert", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun getCurrentLocation(callback: (GeoPoint?) -> Unit) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
                if (location != null) {
                    callback(GeoPoint(location.latitude, location.longitude))
                } else {
                    callback(null)
                }
            }.addOnFailureListener {
                callback(null)
            }
        } else {
            callback(null)
        }
    }

    private fun startLocationUpdates() {
        val locationRequest = LocationRequest.create().apply {
            interval = 4000
            fastestInterval = 3000
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
        }
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, null)
    }

    private fun updateMapLocation(location: Location) {
        val geoPoint = GeoPoint(location.latitude, location.longitude)

        // Remove previous markers
        map.overlays.removeAll { it is Marker }

        val marker = Marker(map)
        marker.position = geoPoint
        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        map.overlays.add(marker)
        map.invalidate()

        // Center the map on the new location only if not exploring or drawing
        if (!isExploring && !isDrawing) {
            map.controller.setCenter(geoPoint)
        }

        // Set initial zoom level if it's the first location update
        if (isInitialLocationUpdate) {
            map.controller.setZoom(15.0)
            isInitialLocationUpdate = false
        }
    }


    private fun drawPath() {
        val currentZoom = map.zoomLevelDouble
        val currentCenter = map.mapCenter as GeoPoint

        map.overlays.removeAll { it is Polyline }
        val polyline = Polyline()
        polyline.setPoints(pathPoints)
        map.overlays.add(polyline)
        map.invalidate()

        map.controller.setZoom(currentZoom)
        map.controller.setCenter(currentCenter)
    }

    private fun checkPermissions() {
        val permissions = arrayOf(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        val permissionsToRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest.toTypedArray(), 2)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 2) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                // All permissions granted
            } else {
                Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show()
            }
        } else if (requestCode == 3) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // CALL_PHONE permission granted
                sendEmergencyAlert()
            } else {
                Toast.makeText(this, "Permission denied to make calls", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(screenOffReceiver)
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    class ScreenOffReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.d("ScreenOffReceiver", "onReceive called with action: ${intent.action}")
            if (intent.action == Intent.ACTION_SCREEN_OFF) {
                val serviceIntent = Intent(context, LocationService::class.java)
                ContextCompat.startForegroundService(context, serviceIntent)
                Log.d("ScreenOffReceiver", "LocationService started")
            }
        }
    }
    class LocationService : Service() {

        private lateinit var fusedLocationClient: FusedLocationProviderClient
        private lateinit var locationCallback: LocationCallback
        private val pathPoints = mutableListOf<GeoPoint>()

        override fun onCreate() {
            super.onCreate()
            fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

            locationCallback = object : LocationCallback() {
                override fun onLocationResult(locationResult: LocationResult) {
                    for (location in locationResult.locations) {
                        Log.d("LocationService", "Location received: ${location.latitude}, ${location.longitude}")
                        checkDistanceFromPath(location)
                    }
                }
            }

            startLocationUpdates()
            createNotificationChannel()
        }

        private fun startLocationUpdates() {
            val locationRequest = LocationRequest.create().apply {
                interval = 10000
                fastestInterval = 5000
                priority = LocationRequest.PRIORITY_HIGH_ACCURACY
            }
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, null)
        }

        private fun checkDistanceFromPath(location: Location) {
            if (pathPoints.isEmpty()) {
                showWarningNotification("No path drawn. Please draw a path first.")
                return
            }

            val userLocation = GeoPoint(location.latitude, location.longitude)
            val maxDistance = 30.0 // Maximum allowed distance in meters

            var isFarFromPath = true
            for (point in pathPoints) {
                try {
                    val distance = userLocation.distanceToAsDouble(point)
                    if (distance <= maxDistance) {
                        isFarFromPath = false
                        break
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    Log.e("LocationService", "Error calculating distance: ${e.message}")
                }
            }

            if (isFarFromPath) {
                showWarningNotification("Too far from the Safe Path. Do you want to contact the emergency contact?")
            }
        }

        private fun showWarningNotification(message: String) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val notification = NotificationCompat.Builder(this, "safe_walk_channel")
                .setContentTitle("Warning")
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()
            notificationManager.notify(1, notification)
        }

        override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
            createNotificationChannel()
            val notification: Notification = NotificationCompat.Builder(this, "safe_walk_channel")
                .setContentTitle("Safe Walk")
                .setContentText("Monitoring your location in the background")
                .setSmallIcon(R.drawable.ic_notification)
                .build()
            startForeground(1, notification)
            Log.d("LocationService", "Foreground service started")
            return START_STICKY
        }

        override fun onDestroy() {
            super.onDestroy()
            fusedLocationClient.removeLocationUpdates(locationCallback)
            Log.d("LocationService", "Service destroyed")
        }

        override fun onBind(intent: Intent?): IBinder? {
            return null
        }

        private fun createNotificationChannel() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val serviceChannel = NotificationChannel(
                    "safe_walk_channel",
                    "Safe Walk Channel",
                    NotificationManager.IMPORTANCE_DEFAULT
                )
                val manager = getSystemService(NotificationManager::class.java)
                manager.createNotificationChannel(serviceChannel)
            }
        }


    }

}

