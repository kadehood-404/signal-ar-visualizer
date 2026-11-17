package com.signalar.visualizer

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.wifi.WifiManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.util.*

class MainActivity : ComponentActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var rotationVectorSensor: Sensor? = null
    
    var azimuth by mutableStateOf(0f)
    var signalStrengthText by mutableStateOf("Initializing...")
    var signalRssi by mutableStateOf(-100)
    var currentMode by mutableStateOf("WIFI") // "WIFI" or "BLE"

    private lateinit var wifiManager: WifiManager
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bleScanner: android.bluetooth.le.BluetoothLeScanner? = null
    private var isScanning = false

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // Handle permissions
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        bleScanner = bluetoothAdapter?.bluetoothLeScanner

        requestPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_WIFI_STATE,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        )

        setContent {
            Surface(modifier = Modifier.fillMaxSize()) {
                ARVisualizerScreen(this)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        rotationVectorSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        startPolling()
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        stopBleScan()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ROTATION_VECTOR) {
            val rotationMatrix = FloatArray(9)
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            val orientationValues = FloatArray(3)
            SensorManager.getOrientation(rotationMatrix, orientationValues)
            azimuth = Math.toDegrees(orientationValues[0].toDouble()).toFloat()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun startPolling() {
        isScanning = true
        Thread {
            while (isScanning) {
                if (currentMode == "WIFI") {
                    try {
                        val rssi = wifiManager.connectionInfo.rssi
                        val ssid = wifiManager.connectionInfo.ssid ?: "Unknown"
                        signalRssi = rssi
                        signalStrengthText = "SSID: $ssid\nRSSI: $rssi dBm"
                    } catch (e: SecurityException) {
                        signalStrengthText = "Location permission missing for Wi-Fi"
                    }
                }
                Thread.sleep(2000)
            }
        }.start()
    }

    fun toggleMode(newMode: String) {
        currentMode = newMode
        if (newMode == "BLE") {
            stopBleScan()
            startBleScan()
        } else {
            stopBleScan()
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.let {
                if (currentMode == "BLE") {
                    val name = it.device.name ?: "Unknown BLE Device"
                    val rssi = it.rssi
                    signalRssi = rssi
                    signalStrengthText = "BLE: $name\nRSSI: $rssi dBm"
                }
            }
        }
    }

    private fun startBleScan() {
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
                bleScanner?.startScan(scanCallback)
            }
        } catch (e: Exception) {
            signalStrengthText = "BLE Scan Error"
        }
    }

    private fun stopBleScan() {
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
                bleScanner?.stopScan(scanCallback)
            }
        } catch (e: Exception) {}
    }
}

@Composable
fun ARVisualizerScreen(activity: MainActivity) {
    val lifecycleOwner = LocalLifecycleOwner.current

    Box(modifier = Modifier.fillMaxSize()) {
        // Camera Viewfinder Background
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                    cameraProviderFuture.addListener({
                        val cameraProvider = cameraProviderFuture.get()
                        val preview = androidx.camera.core.Preview.Builder().build().also {
                            it.setSurfaceProvider(surfaceProvider)
                        }
                        try {
                            cameraProvider.unbindAll()
                            cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview
                            )
                        } catch (exc: Exception) {}
                    }, ContextCompat.getMainExecutor(ctx))
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // HUD Overlay
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top Mode Switcher
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                TabRow(selectedTabIndex = if (activity.currentMode == "WIFI") 0 else 1) {
                    Tab(
                        selected = activity.currentMode == "WIFI",
                        onClick = { activity.toggleMode("WIFI") },
                        text = { Text("Wi-Fi Mode") }
                    )
                    Tab(
                        selected = activity.currentMode == "BLE",
                        onClick = { activity.toggleMode("BLE") },
                        text = { Text("Bluetooth Mode") }
                    )
                }
            }

            // Floating AR Node Indicator in Center
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                val color = when {
                    activity.signalRssi > -60 -> Color.Green
                    activity.signalRssi > -75 -> Color.Yellow
                    else -> Color.Red
                }

                Box(
                    modifier = Modifier
                        .size(100.dp)
                        .background(color.copy(alpha = 0.4f), shape = androidx.compose.foundation.shape.CircleSpec()),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "${activity.signalRssi} dBm",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }

            // Bottom Info Panel
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.7f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = "Heading: ${activity.azimuth.toInt()}°", color = Color.White)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = activity.signalStrengthText, color = Color.Cyan)
                }
            }
        }
    }
}