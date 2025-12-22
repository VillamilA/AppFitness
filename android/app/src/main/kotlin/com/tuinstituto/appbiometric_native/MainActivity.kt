package com.tuinstituto.fitness_tracker

import android.os.Bundle
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterFragmentActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.Executor

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt
import io.flutter.plugin.common.EventChannel

// Importaciones adicionales
import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import androidx.core.app.ActivityCompat
// importacion de Notificaciones

import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * MainActivity: punto de entrada de la aplicación Android
 * - Extiende FlutterFragmentActivity (necesario para BiometricPrompt)
 * - Configura los Platform Channels aquí
 */
class MainActivity: FlutterFragmentActivity() {

    // PASO 1: Definir nombre del canal (DEBE coincidir con Dart)
    private val BIOMETRIC_CHANNEL = "com.tuinstituto.fitness/biometric"
    private val NOTIFICATION_CHANNEL = "com.tuinstituto.fitness/notifications"
    private val NOTIFICATION_CHANNEL_ID = "fitness_notifications"
    private val NOTIFICATION_CHANNEL_NAME = "Fitness Notifications"
    private val NOTIFICATION_PERMISSION_REQUEST_CODE = 1002

    // PASO 2: Variables para biometría
    private lateinit var executor: Executor
    private lateinit var biometricPrompt: BiometricPrompt
    private var pendingResult: MethodChannel.Result? = null

    /**
     * configureFlutterEngine: se llama al iniciar la app
     * AQUÍ configuramos TODOS los Platform Channels
     */
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        // Inicializar executor para biometría
        executor = ContextCompat.getMainExecutor(this)

        // CONFIGURAR PLATFORM CHANNELS
        setupBiometricChannel(flutterEngine)
        setupAccelerometerChannel(flutterEngine)
        setupGpsChannel(flutterEngine)
        setupNotificationChannel(flutterEngine)
        
        // Solicitar permiso de notificaciones (Android 13+)
        requestNotificationPermission()
    }

    /**
     * Solicitar permiso de notificaciones
     */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    NOTIFICATION_PERMISSION_REQUEST_CODE
                )
            }
        }
    }

    /**
     * Configurar biometric channel
     */
    private fun setupBiometricChannel(flutterEngine: FlutterEngine) {
        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            BIOMETRIC_CHANNEL
        ).setMethodCallHandler { call, result ->
            /**
             * setMethodCallHandler: escucha llamadas desde Flutter
             *
             * Parámetros:
             * - call: contiene el nombre del método y argumentos
             * - result: objeto para enviar respuesta a Flutter
             */

            when (call.method) {
                "checkBiometricSupport" -> {
                    // Flutter llamó a checkBiometricSupport()
                    val canAuth = checkBiometricSupport()
                    result.success(canAuth)  // Enviamos respuesta
                }

                "authenticate" -> {
                    // Guardamos result para responder después (async)
                    pendingResult = result
                    showBiometricPrompt()
                }

                else -> {
                    // Método no reconocido
                    result.notImplemented()
                }
            }
        }
    }

    /**
     * Verificar si el dispositivo soporta biometría
     */
    private fun checkBiometricSupport(): Boolean {
        val biometricManager = BiometricManager.from(this)

        return when (biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        )) {
            BiometricManager.BIOMETRIC_SUCCESS -> true
            else -> false
        }
    }

    /**
     * Mostrar diálogo de autenticación biométrica
     */
    private fun showBiometricPrompt() {
        // Configurar información del diálogo
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Autenticación Biométrica")
            .setSubtitle("Usa tu huella dactilar")
            .setDescription("Coloca tu dedo en el sensor")
            .setNegativeButtonText("Cancelar")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()

        // Crear BiometricPrompt con callbacks
        biometricPrompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {

                override fun onAuthenticationSucceeded(
                    result: BiometricPrompt.AuthenticationResult
                ) {
                    super.onAuthenticationSucceeded(result)
                    // ✅ Autenticación exitosa
                    pendingResult?.success(true)
                    pendingResult = null
                }

                override fun onAuthenticationError(
                    errorCode: Int,
                    errString: CharSequence
                ) {
                    super.onAuthenticationError(errorCode, errString)
                    // ❌ Error en autenticación
                    pendingResult?.success(false)
                    pendingResult = null
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    // Usuario puede reintentar
                }
            }
        )

        // Mostrar el diálogo
        biometricPrompt.authenticate(promptInfo)
    }

    // ═══════════════════════════════════════════════════════════
    // ACCELEROMETER CHANNEL
    // ═══════════════════════════════════════════════════════════
    
    private val ACCELEROMETER_CHANNEL = "com.tuinstituto.fitness/accelerometer"

    /**
     * Configurar EventChannel para acelerómetro
 *
 * EXPLICACIÓN DIDÁCTICA:
 * - EventChannel.StreamHandler tiene 2 métodos:
 *   1. onListen: cuando Flutter comienza a escuchar
 *   2. onCancel: cuando Flutter deja de escuchar
 */
private fun setupAccelerometerChannel(flutterEngine: FlutterEngine) {
    val sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
    val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    var stepCount = 0
    var lastMagnitude = 0.0
    var sensorEventListener: SensorEventListener? = null

    // Variables para suavizado
    val magnitudeHistory = mutableListOf<Double>()
    val historySize = 10
    var sampleCount = 0
    var lastActivityType = "stationary"
    var activityConfidence = 0
    
    // 👟 Variables para detección realista de pasos
    var lastStepTime = 0L
    val MIN_STEP_INTERVAL = 250L  // 250ms entre pasos (cadencia realista)
    val STEP_THRESHOLD_LOW = 11.0  // Umbral mínimo
    val STEP_THRESHOLD_HIGH = 20.0 // Umbral máximo (no contar caídas)
    var isPeakDetected = false

    // ═══════════════════════════════════════════════════════════
    // CONFIGURAR EVENT CHANNEL
    // ═══════════════════════════════════════════════════════════
    EventChannel(
        flutterEngine.dartExecutor.binaryMessenger,
        ACCELEROMETER_CHANNEL
    ).setStreamHandler(object : EventChannel.StreamHandler {

        /**
         * onListen: Flutter comenzó a escuchar el stream
         * AQUÍ iniciamos el sensor
         */
        override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
            sensorEventListener = object : SensorEventListener {

                override fun onSensorChanged(event: SensorEvent?) {
                    event?.let {
                        // Calcular magnitud del vector
                        val x = it.values[0]
                        val y = it.values[1]
                        val z = it.values[2]
                        val magnitude = sqrt((x * x + y * y + z * z).toDouble())
                        
                        // 🚨 DETECCIÓN DE CAÍDA: pico > 25 m/s²
                        if (magnitude > 25.0) {
                            // Enviar alerta inmediata
                            val fallData = mapOf(
                                "type" to "fall_detected",
                                "magnitude" to magnitude,
                                "timestamp" to System.currentTimeMillis()
                            )
                            events?.success(fallData)
                            return  // Salir para enviar solo este evento
                        }

                        // Promedio móvil para suavizar
                        magnitudeHistory.add(magnitude)
                        if (magnitudeHistory.size > historySize) {
                            magnitudeHistory.removeAt(0)
                        }
                        val avgMagnitude = magnitudeHistory.average()

                        // 👟 DETECCIÓN REALISTA DE PASOS
                        val currentTime = System.currentTimeMillis()
                        val timeSinceLastStep = currentTime - lastStepTime
                        
                        // Detectar pico (aceleración)
                        if (magnitude > STEP_THRESHOLD_LOW && 
                            magnitude < STEP_THRESHOLD_HIGH && 
                            lastMagnitude <= STEP_THRESHOLD_LOW &&
                            !isPeakDetected) {
                            isPeakDetected = true
                        }
                        
                        // Detectar valle (desaceleración) después del pico
                        if (isPeakDetected && 
                            magnitude < STEP_THRESHOLD_LOW && 
                            lastMagnitude >= STEP_THRESHOLD_LOW &&
                            timeSinceLastStep > MIN_STEP_INTERVAL) {
                            // ✅ Paso válido detectado
                            stepCount++
                            lastStepTime = currentTime
                            isPeakDetected = false
                        }
                        
                        lastMagnitude = magnitude

                        // Determinar actividad (con promedio)
                        val newActivityType = when {
                            avgMagnitude < 10.5 -> "stationary"
                            avgMagnitude < 13.5 -> "walking"
                            else -> "running"
                        }

                        // Solo cambiar si hay confianza
                        if (newActivityType == lastActivityType) {
                            activityConfidence++
                        } else {
                            activityConfidence = 0
                        }

                        val finalActivityType = if (activityConfidence >= 3) {
                            newActivityType
                        } else {
                            lastActivityType
                        }
                        lastActivityType = newActivityType

                        // Enviar cada 3 muestras
                        sampleCount++
                        if (sampleCount >= 3) {
                            sampleCount = 0

                            // ENVIAR DATOS A FLUTTER
                            val data = mapOf(
                                "stepCount" to stepCount,
                                "activityType" to finalActivityType,
                                "magnitude" to avgMagnitude
                            )

                            // events?.success: envía datos al stream
                            events?.success(data)
                        }
                    }
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            }

            // Registrar listener del sensor
            sensorManager.registerListener(
                sensorEventListener,
                accelerometer,
                SensorManager.SENSOR_DELAY_GAME
            )
        }

        /**
         * onCancel: Flutter dejó de escuchar
         * AQUÍ detenemos el sensor
         */
        override fun onCancel(arguments: Any?) {
            sensorEventListener?.let {
                sensorManager.unregisterListener(it)
            }
            sensorEventListener = null
        }
    })

    // MethodChannel auxiliar para control
    MethodChannel(
        flutterEngine.dartExecutor.binaryMessenger,
        "$ACCELEROMETER_CHANNEL/control"
    ).setMethodCallHandler { call, result ->
        when (call.method) {
            "start" -> {
                stepCount = 0
                result.success(null)
            }
            "stop" -> {
                result.success(null)
            }
            "reset" -> {
                stepCount = 0
                result.success(null)
            }
            else -> result.notImplemented()
        }
    }
}

    // ═══════════════════════════════════════════════════════════
    // GPS CHANNEL
    // ═══════════════════════════════════════════════════════════
    
    private val GPS_CHANNEL = "com.tuinstituto.fitness/gps"
    private val LOCATION_PERMISSION_REQUEST_CODE = 1001

    /**
     * Configurar GPS channel
     */
    private fun setupGpsChannel(flutterEngine: FlutterEngine) {
    val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
    var locationListener: LocationListener? = null

    // ═══════════════════════════════════════════════════════════
    // METHOD CHANNEL - Operaciones puntuales
    // ═══════════════════════════════════════════════════════════
    MethodChannel(
        flutterEngine.dartExecutor.binaryMessenger,
        GPS_CHANNEL
    ).setMethodCallHandler { call, result ->
        when (call.method) {
            "isGpsEnabled" -> {
                val isEnabled = locationManager.isProviderEnabled(
                    LocationManager.GPS_PROVIDER
                )
                result.success(isEnabled)
            }

            "requestPermissions" -> {
                if (hasLocationPermission()) {
                    result.success(true)
                } else {
                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        ),
                        LOCATION_PERMISSION_REQUEST_CODE
                    )
                    result.success(hasLocationPermission())
                }
            }

            "getCurrentLocation" -> {
                if (!hasLocationPermission()) {
                    result.error("PERMISSION_DENIED", "Sin permisos", null)
                    return@setMethodCallHandler
                }

                try {
                    val location = locationManager.getLastKnownLocation(
                        LocationManager.GPS_PROVIDER
                    ) ?: locationManager.getLastKnownLocation(
                        LocationManager.NETWORK_PROVIDER
                    )

                    if (location != null) {
                        result.success(locationToMap(location))
                    } else {
                        result.error("NO_LOCATION", "No disponible", null)
                    }
                } catch (e: SecurityException) {
                    result.error("SECURITY_ERROR", e.message, null)
                }
            }

            else -> result.notImplemented()
        }
    }

    // ═══════════════════════════════════════════════════════════
    // EVENT CHANNEL - Stream de ubicaciones
    // ═══════════════════════════════════════════════════════════
    EventChannel(
        flutterEngine.dartExecutor.binaryMessenger,
        "$GPS_CHANNEL/stream"
    ).setStreamHandler(object : EventChannel.StreamHandler {

        override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
            if (!hasLocationPermission()) {
                events?.error("PERMISSION_DENIED", "Sin permisos", null)
                return
            }

            locationListener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    // Enviar ubicación a Flutter
                    events?.success(locationToMap(location))
                }

                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }

            try {
                // Solicitar actualizaciones
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1000L,      // cada 1 segundo
                    0f,         // cualquier distancia
                    locationListener!!
                )
            } catch (e: SecurityException) {
                events?.error("SECURITY_ERROR", e.message, null)
            }
        }

        override fun onCancel(arguments: Any?) {
            locationListener?.let {
                locationManager.removeUpdates(it)
            }
            locationListener = null
        }
    })
}

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun locationToMap(location: Location): Map<String, Any> {
        return mapOf(
            "latitude" to location.latitude,
            "longitude" to location.longitude,
            "altitude" to location.altitude,
            "speed" to location.speed.toDouble(),
            "accuracy" to location.accuracy.toDouble(),
            "timestamp" to location.time
        )
    }

    // ═══════════════════════════════════════════════════════════
    // NOTIFICATION CHANNEL
    // ═══════════════════════════════════════════════════════════

    /**
     * Configurar notification channel
     */
    private fun setupNotificationChannel(flutterEngine: FlutterEngine) {
        createNotificationChannel()
        
        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            NOTIFICATION_CHANNEL
        ).setMethodCallHandler { call, result ->
            when (call.method) {
                "showNotification" -> {
                    val title = call.argument<String>("title") ?: "Notificación"
                    val body = call.argument<String>("body") ?: ""
                    showNotificationNative(title, body)
                    result.success(true)
                }
                else -> result.notImplemented()
            }
        }
    }

    /**
     * Crear canal de notificación (Android 8.0+)
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notificaciones de pasos y actividad"
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Mostrar notificación usando NotificationManager
     */
    private fun showNotificationNative(title: String, body: String) {
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(1001, notification)
    }
}