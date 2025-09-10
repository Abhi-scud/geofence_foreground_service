package com.f2fk.geofence_foreground_service

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.f2fk.geofence_foreground_service.BackgroundWorker.Companion.IS_IN_DEBUG_MODE_KEY
import com.f2fk.geofence_foreground_service.BackgroundWorker.Companion.PAYLOAD_KEY
import com.f2fk.geofence_foreground_service.BackgroundWorker.Companion.ZONE_ID
import com.f2fk.geofence_foreground_service.BackgroundWorker.Companion.LATITUDE
import com.f2fk.geofence_foreground_service.BackgroundWorker.Companion.LONGITUDE
import com.f2fk.geofence_foreground_service.enums.GeofenceServiceAction
import com.f2fk.geofence_foreground_service.utils.extraNameGen
import com.f2fk.geofence_foreground_service.utils.SharedPreferenceHelper
import com.f2fk.geofence_foreground_service.utils.ServiceConfig
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.Granularity
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.util.concurrent.TimeUnit

private fun Context.workManager() = WorkManager.getInstance(this)

class GeofenceForegroundService : Service() {
    private lateinit var fusedLocationProviderClient: FusedLocationProviderClient
    private lateinit var locationRequest: LocationRequest
    private lateinit var locationCallback: LocationCallback

    override fun onCreate() {
        super.onCreate()

        fusedLocationProviderClient =
            LocationServices.getFusedLocationProviderClient(this)

        locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            TimeUnit.SECONDS.toMillis(20)
        ).apply {
            setMinUpdateDistanceMeters(5f)
            setGranularity(Granularity.GRANULARITY_PERMISSION_LEVEL)
            setWaitForAccurateLocation(true)
        }
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                super.onLocationResult(locationResult)

                val location = locationResult.lastLocation
                if (location != null) {
                    val oneOffTaskRequest = OneTimeWorkRequest.Builder(BackgroundWorker::class.java)
                        .setInputData(buildTaskInputData(
                            "locationUpdates",
                            false,
                            "5",
                            location.latitude.toString(),
                            location.longitude.toString()
                        ))
                        .build()

                    this@GeofenceForegroundService.applicationContext
                        .workManager()
                        .enqueueUniqueWork(
                            Constants.bgTaskUniqueName,
                            ExistingWorkPolicy.APPEND_OR_REPLACE,
                            oneOffTaskRequest
                        )

                    Log.d("onLocationResult", "${location.latitude}, ${location.longitude}")
                } else {
                    Log.w("onLocationResult", "Location is null")
                }
            }
        }
    }

    override fun onBind(p0: Intent?): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val geofenceAction = GeofenceServiceAction.valueOf(
            intent.getStringExtra(applicationContext.extraNameGen(Constants.geofenceAction))!!
        )

        val appIcon = intent.getIntExtra(applicationContext.extraNameGen(Constants.appIcon), 0)
        val notificationChannelId = intent.getStringExtra(applicationContext.extraNameGen(Constants.channelId))!!
        val notificationContentTitle = intent.getStringExtra(applicationContext.extraNameGen(Constants.contentTitle))!!
        val notificationContentText = intent.getStringExtra(applicationContext.extraNameGen(Constants.contentText))!!
        val serviceId = intent.getIntExtra(Constants.serviceId, 525600)

        // Build the notification
        val notification = NotificationCompat.Builder(this, notificationChannelId)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSmallIcon(appIcon)
            .setContentTitle(notificationContentTitle)
            .setContentText(notificationContentText)
            .build()

        // ✅ ALWAYS call startForeground first thing
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(serviceId, notification, FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(serviceId, notification)
        }

        // Save config if needed
        if (geofenceAction == GeofenceServiceAction.SETUP) {
            SharedPreferenceHelper.saveServiceConfig(
                applicationContext,
                ServiceConfig(
                    channelId = notificationChannelId,
                    contentTitle = notificationContentTitle,
                    contentText = notificationContentText,
                    appIcon = appIcon,
                    serviceId = serviceId
                )
            )
            subscribeToLocationUpdates()
        } else if (geofenceAction == GeofenceServiceAction.TRIGGER) {
            handleGeofenceEvent(intent)
        }

        return START_STICKY
    }


    private fun handleGeofenceEvent(intent: Intent) {
        try {
            val isInDebugMode: Boolean = intent.getBooleanExtra(
                applicationContext!!.extraNameGen(Constants.isInDebugMode),
                false
            )

            val geofencingEvent = GeofencingEvent.fromIntent(intent)
            if (geofencingEvent?.hasError() == false) {
                val geofenceTransition = geofencingEvent.geofenceTransition

                val triggeringGeoFences = geofencingEvent.triggeringGeofences

                val zoneID: String? = triggeringGeoFences?.first()?.requestId
                val latitude :String? = triggeringGeoFences?.first()?.latitude.toString()
                val longitude :String? = triggeringGeoFences?.first()?.longitude.toString()
            
                Log.e("geoFencePkg", triggeringGeoFences?.first()?.toString() ?: "No geofence triggered") 

                if (zoneID != null) {
                    val oneOffTaskRequest =
                        OneTimeWorkRequest.Builder(BackgroundWorker::class.java)
                            .setInputData(buildTaskInputData(
                                zoneID,
                                isInDebugMode,
                                geofenceTransition.toString(),
                                latitude,
                                longitude
                            ))
                            .build()

                    this.baseContext!!.workManager().enqueueUniqueWork(
                        Constants.bgTaskUniqueName,
                        ExistingWorkPolicy.APPEND_OR_REPLACE,
                        oneOffTaskRequest
                    )
                }
            }
        } catch (e: Exception) {
            println(e.message)
            println(e.toString())
        }
    }

    override fun onDestroy() {
        unsubscribeToLocationUpdates()

        super.onDestroy()
    }

    private fun subscribeToLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return
        }
        fusedLocationProviderClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
        )
    }

    private fun unsubscribeToLocationUpdates() {
        val removeTask = fusedLocationProviderClient.removeLocationUpdates(locationCallback)
        removeTask.addOnCompleteListener { task ->
            if (task.isSuccessful) {
                Log.d("Unsubscribe", "Location Callback removed.")
                stopSelf()
            } else {
                Log.d("Unsubscribe", "Failed to remove Location Callback.")
            }
        }
    }

    private fun buildTaskInputData(
        zoneID: String,
        isInDebugMode: Boolean,
        payload: String?,
        latitude: String?, longitude: String?
    ): Data {
        return Data.Builder()
            .putString(ZONE_ID, zoneID)
            .putBoolean(IS_IN_DEBUG_MODE_KEY, isInDebugMode)
            .putString(LATITUDE, latitude)
            .putString(LONGITUDE, longitude)
            .apply {
                payload?.let {
                    putString(PAYLOAD_KEY, payload)
                    
                }
            }
            .build()
    }
}