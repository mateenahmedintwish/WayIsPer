package com.intwish.wayisper

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import com.google.android.material.textfield.TextInputEditText
import org.json.JSONArray
import org.json.JSONObject
import java.util.*

class ChatActivity : AppCompatActivity() {

    private val TAG = "WayIsPerNearby"
    private val STRATEGY = Strategy.P2P_CLUSTER
    private val SERVICE_ID = "com.intwish.wayisper.CHAT"

    private val messages = mutableListOf<Message>()
    private lateinit var adapter: MessageAdapter
    private var username: String? = null
    
    private val connectedEndpoints = mutableSetOf<String>()
    private val endpointNames = mutableMapOf<String, String>()
    
    private var isHost = false
    private var hostEndpointId: String? = null
    private var roomName = ""
    private var roomPassword = ""

    private lateinit var inputContainer: View
    private lateinit var noParticipantsOverlay: View
    private lateinit var messageEditText: TextInputEditText

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            // Check for name collision
            if (endpointNames.values.any { it.equals(connectionInfo.endpointName, ignoreCase = true) } || 
                connectionInfo.endpointName.equals(username, ignoreCase = true)) {
                Nearby.getConnectionsClient(this@ChatActivity).rejectConnection(endpointId)
                return
            }
            endpointNames[endpointId] = connectionInfo.endpointName
            Nearby.getConnectionsClient(this@ChatActivity).acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                connectedEndpoints.add(endpointId)
                updateUIState()
                if (isHost) syncFullHistoryTo(endpointId)
            } else {
                endpointNames.remove(endpointId)
            }
        }

        override fun onDisconnected(endpointId: String) {
            connectedEndpoints.remove(endpointId)
            endpointNames.remove(endpointId)
            if (endpointId == hostEndpointId) {
                hostEndpointId = null
                promoteToHost()
            }
            updateUIState()
        }
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
                            runOnUiThread {
                                if (messages.none { it.id == id }) {
                                    addMessage(Message(id, text, sender, ts))
                                    if (isHost) relayPayload(payload, endpointId)
                                    sendAck(id)
                                }
                            }
                        }
                        "ACK" -> {
                            val id = json.getString("id")
                            val ackFrom = json.getString("from")
                            runOnUiThread {
                                messages.find { it.id == id }?.let { 
                                    if (it.heardBy.add(ackFrom)) {
                                        adapter.notifyItemChanged(messages.indexOf(it))
                                    }
                                }
                                if (isHost) relayPayload(payload, endpointId)
                            }
                        }
                        "SYNC" -> {
                            val data = json.getJSONArray("data")
                            runOnUiThread {
                                for (i in 0 until data.length()) {
                                    val m = data.getJSONObject(i)
                                    val id = m.getString("id")
                                    if (messages.none { it.id == id }) {
                                        addMessage(Message(
                                            id, m.getString("text"), 
                                            m.getString("sender"), m.getLong("ts")
                                        ))
                                    }
                                }
                            }
                        }
                        "HOST_ANNOUNCE" -> {
                            hostEndpointId = endpointId
                            isHost = false
                            Nearby.getConnectionsClient(this@ChatActivity).stopAdvertising()
                            Log.d(TAG, "New host announced: $endpointId")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Payload parse error", e)
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        isHost = intent.getBooleanExtra("isHost", false)
        roomPassword = intent.getStringExtra("password") ?: ""
        roomName = intent.getStringExtra("roomName") ?: "Room"
        hostEndpointId = intent.getStringExtra("endpointId")

        val sharedPref = getSharedPreferences("app_prefs", MODE_PRIVATE)
        username = sharedPref.getString("username", Build.MODEL)

        findViewById<TextView>(R.id.toolbarTitle)?.text = roomName

        inputContainer = findViewById(R.id.inputContainer)
        noParticipantsOverlay = findViewById(R.id.noParticipantsOverlay)
        messageEditText = findViewById(R.id.messageEditText)

        val recyclerView = findViewById<RecyclerView>(R.id.messagesRecyclerView)
        adapter = MessageAdapter(messages, username ?: Build.MODEL)
        recyclerView.adapter = adapter

        findViewById<View>(R.id.participantsButton).setOnClickListener { showParticipantsDialog() }
        findViewById<View>(R.id.sendButton).setOnClickListener {
            val text = messageEditText.text.toString().trim()
            if (text.isNotEmpty()) {
                val messageId = UUID.randomUUID().toString().substring(0, 8)
                val ts = System.currentTimeMillis()
                addMessage(Message(messageId, text, username ?: Build.MODEL, ts))
                messageEditText.text?.clear()
                
                val json = JSONObject().apply {
                    put("type", "MSG")
                    put("id", messageId)
                    put("text", text)
                    put("sender", username)
                    put("ts", ts)
                }
                broadcastPayload(Payload.fromBytes(json.toString().toByteArray(Charsets.UTF_8)))
            }
        }

        updateUIState()
        checkPermissionsAndStart()
    }

    private fun updateUIState() {
        runOnUiThread {
            val noParticipants = connectedEndpoints.isEmpty()
            inputContainer.visibility = if (noParticipants) View.GONE else View.VISIBLE
            noParticipantsOverlay.visibility = if (noParticipants) View.VISIBLE else View.GONE
        }
    }

    private fun promoteToHost() {
        isHost = true
        startAdvertising()
        val json = JSONObject().put("type", "HOST_ANNOUNCE")
        broadcastPayload(Payload.fromBytes(json.toString().toByteArray(Charsets.UTF_8)))
        Toast.makeText(this, "You have been promoted to host", Toast.LENGTH_SHORT).show()
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
            })
        }
        val json = JSONObject().apply {
            put("type", "SYNC")
            put("data", array)
        }
        Nearby.getConnectionsClient(this).sendPayload(endpointId, Payload.fromBytes(json.toString().toByteArray(Charsets.UTF_8)))
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

    private fun broadcastPayload(payload: Payload) {
        if (connectedEndpoints.isNotEmpty()) {
            Nearby.getConnectionsClient(this).sendPayload(connectedEndpoints.toList(), payload)
        }
    }

    private fun startNearby() {
        if (isHost) startAdvertising() else {
            startDiscovery()
            hostEndpointId?.let {
                Nearby.getConnectionsClient(this).requestConnection(username ?: Build.MODEL, it, connectionLifecycleCallback)
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
                    Nearby.getConnectionsClient(this@ChatActivity).requestConnection(username ?: Build.MODEL, endpointId, connectionLifecycleCallback)
                }
            }
            override fun onEndpointLost(endpointId: String) {}
        }, DiscoveryOptions.Builder().setStrategy(STRATEGY).build())
    }

    private fun addMessage(message: Message) {
        messages.add(message)
        messages.sortBy { it.timestamp }
        adapter.notifyDataSetChanged()
        findViewById<RecyclerView>(R.id.messagesRecyclerView).scrollToPosition(messages.size - 1)
    }

    private fun showParticipantsDialog() {
        val names = endpointNames.values.toList()
        AlertDialog.Builder(this).setTitle("Participants").setItems(names.toTypedArray(), null).show()
    }

    private fun checkPermissionsAndStart() {
        val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.addAll(listOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT))
        }
        val missing = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), 100) else startNearby()
    }

    override fun onDestroy() {
        super.onDestroy()
        Nearby.getConnectionsClient(this).stopAllEndpoints()
    }
}
