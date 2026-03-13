package com.intwish.wayisper

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.*

class NearbyService : Service() {

    private val TAG = "WayIsPerService"
    private val STRATEGY = Strategy.P2P_CLUSTER
    private val SERVICE_ID = "com.intwish.wayisper.CHAT"
    private val CHANNEL_ID = "NearbyChatChannel"
    private val NOTIFICATION_ID = 1001

    private val binder = NearbyBinder()
    private var serviceListener: ServiceListener? = null

    val messages = mutableListOf<Message>()
    val connectedEndpoints = mutableSetOf<String>()
    val endpointNames = mutableMapOf<String, String>()
    
    var isHost = false
    var hostEndpointId: String? = null
    var roomName = ""
    var roomPassword = ""
    var username = ""

    interface ServiceListener {
        fun onMessagesUpdated()
        fun onEndpointsUpdated()
        fun onHostPromoted()
        fun onRoomRenamed(newName: String)
    }

    inner class NearbyBinder : Binder() {
        fun getService(): NearbyService = this@NearbyService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    fun setListener(listener: ServiceListener?) {
        serviceListener = listener
    }

    fun startNearby(context: Context) {
        startForeground(NOTIFICATION_ID, createNotification("WayIsPer", "Running in background"))
        if (isHost) startAdvertising() else {
            startDiscovery()
            hostEndpointId?.let {
                Nearby.getConnectionsClient(this).requestConnection(username, it, connectionLifecycleCallback)
            }
        }
    }

    private fun startAdvertising() {
        val adName = "$roomName|${roomPassword.isNotEmpty()}"
        Nearby.getConnectionsClient(this).startAdvertising(
            adName, SERVICE_ID, connectionLifecycleCallback,
            AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
        )
    }

    private fun startDiscovery() {
        Nearby.getConnectionsClient(this).startDiscovery(SERVICE_ID, object : EndpointDiscoveryCallback() {
            override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
                if (hostEndpointId == null || endpointId == hostEndpointId) {
                    Nearby.getConnectionsClient(this@NearbyService).requestConnection(username, endpointId, connectionLifecycleCallback)
                }
            }
            override fun onEndpointLost(endpointId: String) {}
        }, DiscoveryOptions.Builder().setStrategy(STRATEGY).build())
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            if (endpointNames.values.any { it.equals(connectionInfo.endpointName, ignoreCase = true) } || 
                connectionInfo.endpointName.equals(username, ignoreCase = true)) {
                Nearby.getConnectionsClient(this@NearbyService).rejectConnection(endpointId)
                return
            }
            endpointNames[endpointId] = connectionInfo.endpointName
            Nearby.getConnectionsClient(this@NearbyService).acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                connectedEndpoints.add(endpointId)
                val name = endpointNames[endpointId] ?: "Someone"
                addSystemMessage("$name entered the chat")
                serviceListener?.onEndpointsUpdated()
                if (isHost) syncFullHistoryTo(endpointId)
            } else {
                endpointNames.remove(endpointId)
            }
        }

        override fun onDisconnected(endpointId: String) {
            val name = endpointNames[endpointId] ?: "Someone"
            addSystemMessage("$name left the chat")
            connectedEndpoints.remove(endpointId)
            endpointNames.remove(endpointId)
            if (endpointId == hostEndpointId) {
                hostEndpointId = null
                promoteToHost()
            }
            serviceListener?.onEndpointsUpdated()
        }
    }

    private fun addSystemMessage(text: String) {
        val id = UUID.randomUUID().toString().substring(0, 8)
        val message = Message(id, text, "System", System.currentTimeMillis(), type = MessageType.SYSTEM)
        messages.add(message)
        messages.sortBy { it.timestamp }
        serviceListener?.onMessagesUpdated()
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            payload.asBytes()?.let { bytes ->
                try {
                    val json = JSONObject(String(bytes, Charsets.UTF_8))
                    when (json.getString("type")) {
                        "MSG" -> {
                            val id = json.getString("id")
                            val text = json.getString("text")
                            val sender = json.getString("sender")
                            val ts = json.getLong("ts")
                            if (messages.none { it.id == id }) {
                                val message = Message(id, text, sender, ts)
                                messages.add(message)
                                messages.sortBy { it.timestamp }
                                serviceListener?.onMessagesUpdated()
                                
                                triggerHapticFeedback()
                                showMessageNotification(sender, text)
                                
                                if (isHost) relayPayload(payload, endpointId)
                                sendAck(id)
                            }
                        }
                        "ACK" -> {
                            val id = json.getString("id")
                            val ackFrom = json.getString("from")
                            messages.find { it.id == id }?.let { 
                                if (it.heardBy.add(ackFrom)) {
                                    serviceListener?.onMessagesUpdated()
                                }
                            }
                            if (isHost) relayPayload(payload, endpointId)
                        }
                        "SYNC" -> {
                            val data = json.getJSONArray("data")
                            var updated = false
                            for (i in 0 until data.length()) {
                                val m = data.getJSONObject(i)
                                val id = m.getString("id")
                                val typeStr = m.optString("type", "USER")
                                val type = if (typeStr == "SYSTEM") MessageType.SYSTEM else MessageType.USER
                                if (messages.none { it.id == id }) {
                                    messages.add(Message(
                                        id, m.getString("text"), 
                                        m.getString("sender"), m.getLong("ts"),
                                        type = type
                                    ))
                                    updated = true
                                }
                            }
                            if (updated) {
                                messages.sortBy { it.timestamp }
                                serviceListener?.onMessagesUpdated()
                            }
                        }
                        "HOST_ANNOUNCE" -> {
                            hostEndpointId = endpointId
                            isHost = false
                            Nearby.getConnectionsClient(this@NearbyService).stopAdvertising()
                            serviceListener?.onHostPromoted()
                        }
                        "RENAME" -> {
                            val newName = json.getString("newName")
                            val oldName = roomName
                            val renamedBy = json.getString("by")
                            roomName = newName
                            addSystemMessage("$renamedBy renamed $oldName to $newName")
                            serviceListener?.onRoomRenamed(newName)
                            if (isHost) {
                                relayPayload(payload, endpointId)
                                // Re-advertise with new name
                                Nearby.getConnectionsClient(this@NearbyService).stopAdvertising()
                                startAdvertising()
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Payload parse error", e)
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }

    fun renameRoom(newName: String) {
        val oldName = roomName
        roomName = newName
        addSystemMessage("$username renamed $oldName to $newName")
        serviceListener?.onRoomRenamed(newName)
        
        val json = JSONObject().apply {
            put("type", "RENAME")
            put("newName", newName)
            put("by", username)
        }
        broadcastPayload(Payload.fromBytes(json.toString().toByteArray(Charsets.UTF_8)))
        
        if (isHost) {
            Nearby.getConnectionsClient(this).stopAdvertising()
            startAdvertising()
        }
    }

    private fun triggerHapticFeedback() {
        val sharedPref = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        if (sharedPref.getBoolean("haptic_enabled", true)) {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(50)
            }
        }
    }

    private fun showMessageNotification(from: String, text: String) {
        if (serviceListener == null) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val intent = Intent(this, ChatActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Message in $roomName")
                .setContentText("$from: $text")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()

            notificationManager.notify(Random().nextInt(), notification)
        }
    }

    private fun promoteToHost() {
        isHost = true
        startAdvertising()
        val json = JSONObject().put("type", "HOST_ANNOUNCE")
        broadcastPayload(Payload.fromBytes(json.toString().toByteArray(Charsets.UTF_8)))
        serviceListener?.onHostPromoted()
    }

    fun broadcastPayload(payload: Payload) {
        if (connectedEndpoints.isNotEmpty()) {
            Nearby.getConnectionsClient(this).sendPayload(connectedEndpoints.toList(), payload)
        }
    }

    private fun relayPayload(payload: Payload, exceptId: String) {
        val targets = connectedEndpoints.filter { it != exceptId }
        if (targets.isNotEmpty()) Nearby.getConnectionsClient(this).sendPayload(targets, payload)
    }

    private fun sendAck(messageId: String) {
        val json = JSONObject().apply {
            put("type", "ACK")
            put("id", messageId)
            put("from", username)
        }
        broadcastPayload(Payload.fromBytes(json.toString().toByteArray(Charsets.UTF_8)))
    }

    private fun syncFullHistoryTo(endpointId: String) {
        if (messages.isEmpty()) return
        val array = JSONArray()
        messages.forEach { m ->
            array.put(JSONObject().apply {
                put("id", m.id)
                put("text", m.text)
                put("sender", m.sender)
                put("ts", m.timestamp)
                put("type", m.type.name)
            })
        }
        val json = JSONObject().apply {
            put("type", "SYNC")
            put("data", array)
        }
        Nearby.getConnectionsClient(this).sendPayload(endpointId, Payload.fromBytes(json.toString().toByteArray(Charsets.UTF_8)))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Nearby Chat Notifications",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(title: String, content: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        Nearby.getConnectionsClient(this).stopAllEndpoints()
        Nearby.getConnectionsClient(this).stopAdvertising()
        Nearby.getConnectionsClient(this).stopDiscovery()
    }
}
