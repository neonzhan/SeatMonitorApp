// MainActivity.kt

package com.example.seatmonitorapp

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.seatmonitorapp.model.ApiResponse
import com.example.seatmonitorapp.model.SeatState
import com.example.seatmonitorapp.network.RetrofitClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import retrofit2.Response
import java.util.*

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SeatMonitorApp"
        private const val SCAN_PERIOD: Long = 30000 // 30 seconds
        private const val SERVICE_UUID = "19b10010-e8f2-537e-4f6c-d104768a1214"
        private const val CHARACTERISTIC_UUID = "19b10012-e8f2-537e-4f6c-d104768a1214"
        private const val CLIENT_CHARACTERISTIC_CONFIG_UUID = "00002902-0000-1000-8000-00805f9b34fb"
        private const val PERMISSION_REQUEST_CODE = 1
    }

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var scanning = false
    private val handler = Handler(Looper.getMainLooper())
    private var bluetoothGatt: BluetoothGatt? = null

    // Required Permissions
    private val requiredPermissions = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT
    )

    // UI Elements
    private lateinit var textViewSeatState: TextView
    private lateinit var buttonStartScan: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Set the content view to your XML layout
        setContentView(R.layout.activity_main)

        // Initialize UI components
        textViewSeatState = findViewById(R.id.textViewSeatState)
        buttonStartScan = findViewById(R.id.buttonStartScan)

        // Set click listener for the button
        buttonStartScan.setOnClickListener {
            onStartScanClicked()
        }

        // Initialize Bluetooth and other components
        initializeBluetooth()
    }

    private fun initializeBluetooth() {
        // Initialize Bluetooth
        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) {
            Log.e(TAG, "Bluetooth not enabled")
            // Optionally, prompt the user to enable Bluetooth
            return
        }

        bluetoothLeScanner = bluetoothAdapter!!.bluetoothLeScanner

        // Check and request permissions
        if (!hasPermissions()) {
            requestPermissions()
        }
    }

    private fun onStartScanClicked() {
        if (!hasPermissions()) {
            requestPermissions()
        } else {
            startScan()
        }
    }

    private fun hasPermissions(): Boolean {
        return requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(this, requiredPermissions, PERMISSION_REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                startScan()
            } else {
                Log.e(TAG, "Required permissions denied. Cannot scan for Bluetooth devices.")
                // Optionally, show a dialog explaining why permissions are needed
            }
        }
    }

    private fun startScan() {
        if (scanning) return

        if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) {
            Log.e(TAG, "Bluetooth is disabled.")
            return
        }

        handler.postDelayed({
            scanning = false
            bluetoothLeScanner?.stopScan(leScanCallback)
            Log.i(TAG, "Scan stopped")
        }, SCAN_PERIOD)

        scanning = true

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        // Start scan without filters
        bluetoothLeScanner?.startScan(null, scanSettings, leScanCallback)
        Log.i(TAG, "Scan started without filters")
    }

    private val leScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.let {
                val deviceName = it.device.name ?: "Unknown"
                val deviceAddress = it.device.address
                Log.i(TAG, "Device found: $deviceName, Address: $deviceAddress")

                // Log advertised service UUIDs
                val uuids = it.scanRecord?.serviceUuids
                if (uuids != null) {
                    for (uuid in uuids) {
                        Log.i(TAG, "Found service UUID: ${uuid.uuid}")
                    }
                }

                // Check if this is the device we're looking for
                if (deviceName == "SeatMonitor" || (uuids != null && uuids.contains(ParcelUuid.fromString(SERVICE_UUID)))) {
                    Log.i(TAG, "SeatMonitor found. Connecting...")
                    bluetoothLeScanner?.stopScan(this)
                    scanning = false
                    connectToDevice(it.device)
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed with error code: $errorCode")
        }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions()
            return
        }
        bluetoothGatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        Log.i(TAG, "Connecting to GATT server...")
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(
            gatt: BluetoothGatt,
            status: Int,
            newState: Int
        ) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "Connected to GATT server")
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "Disconnected from GATT server")
                bluetoothGatt?.close()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(UUID.fromString(SERVICE_UUID))
                if (service != null) {
                    val characteristic = service.getCharacteristic(UUID.fromString(CHARACTERISTIC_UUID))
                    if (characteristic != null) {
                        val success = gatt.setCharacteristicNotification(characteristic, true)
                        Log.i(TAG, "Notification set: $success")

                        val descriptor = characteristic.getDescriptor(UUID.fromString(CLIENT_CHARACTERISTIC_CONFIG_UUID))
                        if (descriptor != null) {
                            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            gatt.writeDescriptor(descriptor)
                        } else {
                            Log.e(TAG, "Descriptor not found")
                        }
                    } else {
                        Log.e(TAG, "Characteristic not found")
                    }
                } else {
                    Log.e(TAG, "Service not found")
                }
            } else {
                Log.e(TAG, "onServicesDiscovered received: $status")
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == UUID.fromString(CHARACTERISTIC_UUID)) {
                val value = characteristic.value[0].toInt()
                Log.i(TAG, "Received seat state: $value")
                val state = if (value == 1) "Normal" else "Reclined"
                uploadSeatState(state)
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "Descriptor written successfully")
            } else {
                Log.e(TAG, "Failed to write descriptor: $status")
            }
        }
    }

    private fun uploadSeatState(state: String) {
        // Update the UI on the main thread
        runOnUiThread {
            textViewSeatState.text = "Seat State: $state"
        }

        // Upload the state to your backend
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val seatState = SeatState(state)
                val response: Response<ApiResponse> = RetrofitClient.instance.sendSeatState(seatState)
                if (response.isSuccessful) {
                    val apiResponse = response.body()
                    Log.i(TAG, "Seat state '$state' uploaded successfully: ${apiResponse?.message}")
                } else {
                    Log.e(TAG, "Failed to upload seat state: ${response.errorBody()?.string()}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error uploading seat state: ${e.message}", e)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bluetoothGatt?.close()
    }
}
