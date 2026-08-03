package com.pokerhelper.app

import android.bluetooth.*
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.UUID

/**
 * ESP32 BLE Client Manager
 * 连接ESP32的Nordic UART Service，发送tap指令
 */
class Esp32BleManager(private val context: Context) {
    
    companion object {
        private const val TAG = "Esp32BleManager"
        
        // Nordic UART Service UUIDs (与ESP32固件一致)
        private val NUS_SERVICE_UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DAB9E9")
        private val RX_CHAR_UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DAB9E9")  // Write
        private val TX_CHAR_UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DAB9E9")  // Notify
        
        // ESP32设备名
        private const val DEVICE_NAME = "QingYun-ESP32"
    }
    
    private val handler = Handler(Looper.getMainLooper())
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    
    // V2.9.183: BLE自动重连
    private var lastConnectedDevice: BluetoothDevice? = null
    private var autoReconnectEnabled = true
    private var reconnectAttempts = 0
    private val MAX_RECONNECT_ATTEMPTS = 5
    private val RECONNECT_DELAY_BASE = 2000L  // 基础重连延迟2秒
    private var reconnectRunnable: Runnable? = null
    
    var isConnected = false
        private set
    var onStatusChanged: ((Boolean, String) -> Unit)? = null
    var onCommandResult: ((String) -> Unit)? = null

    // V2.9.184: BLE命令队列——避免writeCharacteristic失败导致命令丢失
    private val commandQueue = mutableListOf<String>()
    private var isWriting = false

    // V2.9.178: BLE数据缓冲——ESP32 status响应120+字节，BLE单包最多20字节
    // 必须拼接多包才能拿到完整数据
    private val bleRxBuffer = StringBuilder()
    private val bleFlushTimeout = Runnable { flushBleBuffer() }
    
    private fun flushBleBuffer() {
        if (bleRxBuffer.isNotEmpty()) {
            val msg = bleRxBuffer.toString()
            bleRxBuffer.clear()
            Log.d(TAG, "BLE flush complete msg(${msg.length}): $msg")
            onCommandResult?.invoke(msg)
        }
    }

    // V2.9.171: 运行时权限检查
    private fun hasBlePermission(perm: String): Boolean {
        return ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
    }

    // 扫描/连接ESP32设备
    fun startScan() {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter

        if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) {
            notifyStatus(false, "蓝牙未开启")
            return
        }

        // V2.9.171: BLUETOOTH_CONNECT权限检查
        if (!hasBlePermission(android.Manifest.permission.BLUETOOTH_CONNECT)) {
            Log.e(TAG, "BLUETOOTH_CONNECT not granted")
            notifyStatus(false, "蓝牙连接权限未授予，请打开App权限设置允许")
            return
        }

        // 策略1: 从已配对设备列表中查找ESP32
        try {
            val bondedDevices = bluetoothAdapter!!.bondedDevices
            for (device in bondedDevices) {
                val name = try { device.name } catch (e: SecurityException) { null }
                if (name == DEVICE_NAME) {
                    Log.i(TAG, "Found ESP32 in bonded devices: $name")
                    connectToDevice(device)
                    return
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "getBondedDevices SecurityException", e)
        } catch (e: Exception) {
            Log.w(TAG, "getBondedDevices error", e)
        }

        // V2.9.171: BLUETOOTH_SCAN权限检查
        if (!hasBlePermission(android.Manifest.permission.BLUETOOTH_SCAN)) {
            Log.e(TAG, "BLUETOOTH_SCAN not granted")
            notifyStatus(false, "蓝牙扫描权限未授予，请打开App权限设置允许")
            return
        }

        // 策略2: BLE扫描兜底
        notifyStatus(false, "扫描ESP32中...")

        val scanner = try {
            bluetoothAdapter?.bluetoothLeScanner
        } catch (e: SecurityException) {
            Log.e(TAG, "bluetoothLeScanner SecurityException", e)
            null
        }

        if (scanner == null) {
            notifyStatus(false, "蓝牙扫描器不可用")
            return
        }

        try {
            scanner.startScan(scanCallback)
        } catch (e: SecurityException) {
            Log.e(TAG, "startScan SecurityException", e)
            notifyStatus(false, "蓝牙扫描异常")
            return
        }

        // 10秒超时
        handler.postDelayed({
            if (!isConnected) {
                try { scanner.stopScan(scanCallback) } catch (_: Exception) {}
                notifyStatus(false, "未找到ESP32")
            }
        }, 10000)
    }
    
    // 停止扫描
    fun stopScan() {
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            Log.w(TAG, "stopScan error", e)
        }
    }
    
    // 连接指定设备
    private fun connectToDevice(device: BluetoothDevice) {
        stopScan()
        val deviceName = try { device.name } catch (e: SecurityException) { "ESP32" }
        notifyStatus(false, "连接${deviceName}...")
        
        try {
            // 强制BLE传输模式（不走经典蓝牙）
            bluetoothGatt = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(context, false, gattCallback)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "connectGatt SecurityException - need BLUETOOTH_CONNECT permission", e)
            notifyStatus(false, "需要蓝牙连接权限，请在App权限中允许")
        } catch (e: Exception) {
            Log.e(TAG, "connectGatt failed", e)
            notifyStatus(false, "连接失败: ${e.message}")
        }
    }
    
    // 断开连接
    fun disconnect() {
        try {
            autoReconnectEnabled = false  // V2.9.183: 主动断开时不自动重连
            reconnectRunnable?.let { handler.removeCallbacks(it) }
            reconnectRunnable = null
            reconnectAttempts = 0
            handler.removeCallbacks(bleFlushTimeout)
            bleRxBuffer.clear()
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
            bluetoothGatt = null
            txCharacteristic = null
            rxCharacteristic = null
            isConnected = false
            lastConnectedDevice = null
            notifyStatus(false, "已断开")
        } catch (e: Exception) {
            Log.w(TAG, "disconnect error", e)
        }
    }
    
    // 发送tap指令
    fun sendTap(x: Int, y: Int, duration: Int = 50) {
        if (!isConnected || txCharacteristic == null) {
            onCommandResult?.invoke("err:not_connected")
            return
        }
        
        val cmd = "tap:$x,$y,$duration"
        sendCommand(cmd)
    }
    
    // 发送status查询
    fun sendStatus() {
        if (!isConnected || txCharacteristic == null) {
            onCommandResult?.invoke("err:not_connected")
            return
        }
        sendCommand("status")
    }
    
    // 发送ping
    fun sendPing() {
        if (!isConnected || txCharacteristic == null) {
            onCommandResult?.invoke("err:not_connected")
            return
        }
        sendCommand("ping")
    }
    
    // V2.9.184: 命令队列——避免并发写入导致命令丢失
    private fun sendCommand(cmd: String) {
        if (isWriting) {
            commandQueue.add(cmd)
            return
        }
        writeCommand(cmd)
    }
    
    private fun writeCommand(cmd: String) {
        try {
            val characteristic = txCharacteristic ?: run {
                onCommandResult?.invoke("err:no_tx_char")
                processNextCommand()
                return
            }
            characteristic.value = cmd.toByteArray(Charsets.UTF_8)
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            val success = bluetoothGatt?.writeCharacteristic(characteristic) ?: false
            if (success) {
                isWriting = true
            } else {
                onCommandResult?.invoke("err:write_failed")
                processNextCommand()
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendCommand error", e)
            onCommandResult?.invoke("err:${e.message}")
            processNextCommand()
        }
    }
    
    private fun processNextCommand() {
        isWriting = false
        if (commandQueue.isNotEmpty()) {
            val next = commandQueue.removeAt(0)
            writeCommand(next)
        }
    }
    
    // BLE扫描回调
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = device.name ?: return
            
            if (name == DEVICE_NAME) {
                Log.i(TAG, "Found ESP32: $name")
                connectToDevice(device)
            }
        }
        
        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed: $errorCode")
            notifyStatus(false, "扫描失败: $errorCode")
        }
    }
    
    // GATT回调
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "Connected to GATT server")
                    // V2.9.183: 保存设备用于自动重连
                    lastConnectedDevice = gatt.device
                    reconnectAttempts = 0
                    handler.post {
                        // V2.9.179: 请求最大MTU，减少分包
                        gatt.requestMtu(512)
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "Disconnected from GATT server")
                    isConnected = false
                    notifyStatus(false, "已断开")
                    // V2.9.183: 自动重连
                    scheduleReconnect()
                }
            }
        }
        
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.i(TAG, "MTU negotiated: $mtu (status=$status)")
            // MTU协商完成后发现服务
            handler.post { gatt.discoverServices() }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(NUS_SERVICE_UUID)
                if (service != null) {
                    txCharacteristic = service.getCharacteristic(RX_CHAR_UUID)  // 手机写入→ESP32
                    rxCharacteristic = service.getCharacteristic(TX_CHAR_UUID)  // ESP32通知→手机
                    
                    if (txCharacteristic != null && rxCharacteristic != null) {
                        // 启用TX通知
                        gatt.setCharacteristicNotification(rxCharacteristic, true)
                        val descriptor = rxCharacteristic?.getDescriptor(
                            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
                        )
                        descriptor?.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        gatt.writeDescriptor(descriptor)
                        
                        isConnected = true
                        notifyStatus(true, "已连接")
                    } else {
                        notifyStatus(false, "未找到NUS特征")
                    }
                } else {
                    notifyStatus(false, "未找到NUS服务")
                }
            } else {
                notifyStatus(false, "服务发现失败: $status")
            }
        }
        
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == TX_CHAR_UUID) {
                val value = characteristic.getStringValue(0)
                Log.d(TAG, "BLE rx chunk(${value.length}): $value")
                
                // V2.9.179 fix: 不再按\n flush，因为ESP32的status多包响应里自带\n
                // 第一包到\n就flush的话，后续包全丢了
                // 改为纯超时 flush，等所有分包的碎片全部拼完
                handler.removeCallbacks(bleFlushTimeout)
                bleRxBuffer.append(value)
                handler.postDelayed(bleFlushTimeout, 500)
            }
        }
        
        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Write failed: $status")
            }
            processNextCommand()  // V2.9.184: 发送队列中的下一条命令
        }
    }
    
    // V2.9.183: 自动重连调度
    private fun scheduleReconnect() {
        if (!autoReconnectEnabled) return
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Log.w(TAG, "重连已达最大次数($MAX_RECONNECT_ATTEMPTS)，停止重连")
            notifyStatus(false, "重连失败(已达上限)")
            return
        }
        val device = lastConnectedDevice ?: return
        reconnectAttempts++
        val delay = RECONNECT_DELAY_BASE * reconnectAttempts  // 递增延迟
        Log.i(TAG, "BLE自动重连: 第${reconnectAttempts}次, ${delay}ms后")
        notifyStatus(false, "重连中(${reconnectAttempts}/$MAX_RECONNECT_ATTEMPTS)...")
        
        reconnectRunnable?.let { handler.removeCallbacks(it) }
        reconnectRunnable = Runnable {
            try {
                connectToDevice(device)
            } catch (e: Exception) {
                Log.e(TAG, "重连异常", e)
                scheduleReconnect()  // 失败后继续重试
            }
        }
        handler.postDelayed(reconnectRunnable!!, delay)
    }
    
    // 通知状态变化
    private fun notifyStatus(connected: Boolean, message: String) {
        handler.post {
            isConnected = connected
            onStatusChanged?.invoke(connected, message)
        }
    }
}
