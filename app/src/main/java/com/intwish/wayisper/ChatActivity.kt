package com.intwish.wayisper

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.*
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.nearby.connection.Payload
import com.google.android.material.textfield.TextInputEditText
import org.json.JSONObject
import java.util.*

class ChatActivity : AppCompatActivity(), NearbyService.ServiceListener {

    private val TAG = "WayIsPerChat"
    
    private lateinit var adapter: MessageAdapter
    private var username: String? = null
    
    private lateinit var inputContainer: View
    private lateinit var noParticipantsOverlay: View
    private lateinit var messageEditText: TextInputEditText
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var participantsAdapter: ParticipantsAdapter
    private lateinit var toolbarTitle: TextView

    private var nearbyService: NearbyService? = null
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as NearbyService.NearbyBinder
            nearbyService = binder.getService()
            isBound = true
            
            nearbyService?.let {
                it.setListener(this@ChatActivity)
                
                // Initialize service with room info if it's not already running
                if (it.roomName.isEmpty()) {
                    it.isHost = intent.getBooleanExtra("isHost", false)
                    it.roomName = intent.getStringExtra("roomName") ?: "Room"
                    it.roomPassword = intent.getStringExtra("password") ?: ""
                    it.hostEndpointId = intent.getStringExtra("endpointId")
                    it.username = username ?: Build.MODEL
                    it.startNearby(this@ChatActivity)
                }
                
                // Sync UI with service state
                toolbarTitle.text = it.roomName
                adapter.setMessages(it.messages)
                updateUIState()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            nearbyService = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        toolbarTitle = findViewById(R.id.toolbarTitle)
        toolbarTitle.text = intent.getStringExtra("roomName") ?: "Room"
        
        val sharedPref = getSharedPreferences("app_prefs", MODE_PRIVATE)
        username = sharedPref.getString("username", Build.MODEL)

        drawerLayout = findViewById(R.id.drawerLayout)
        inputContainer = findViewById(R.id.inputContainer)
        noParticipantsOverlay = findViewById(R.id.noParticipantsOverlay)
        messageEditText = findViewById(R.id.messageEditText)

        val recyclerView = findViewById<RecyclerView>(R.id.messagesRecyclerView)
        adapter = MessageAdapter(mutableListOf(), username ?: Build.MODEL)
        recyclerView.adapter = adapter

        val participantsRecyclerView = findViewById<RecyclerView>(R.id.participantsRecyclerView)
        participantsAdapter = ParticipantsAdapter()
        participantsRecyclerView.layoutManager = LinearLayoutManager(this)
        participantsRecyclerView.adapter = participantsAdapter

        findViewById<View>(R.id.participantsButton).setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.END)
        }

        findViewById<View>(R.id.renameRoomButton).setOnClickListener {
            showRenameDialog()
        }

        findViewById<View>(R.id.exitChatButton).setOnClickListener {
            exitChatRoom()
        }

        findViewById<View>(R.id.sendButton).setOnClickListener {
            val text = messageEditText.text.toString().trim()
            if (text.isNotEmpty()) {
                sendMessage(text)
            }
        }

        checkPermissionsAndStart()
    }

    private fun showRenameDialog() {
        val input = EditText(this)
        input.setText(nearbyService?.roomName)
        input.setSelection(input.text.length)

        AlertDialog.Builder(this)
            .setTitle("Rename Chatroom")
            .setView(input)
            .setPositiveButton("Rename") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    nearbyService?.renameRoom(newName)
                    drawerLayout.closeDrawer(GravityCompat.END)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun checkPermissionsAndStart() {
        val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.addAll(listOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        
        val missing = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 100)
        } else {
            bindToService()
        }
    }

    private fun bindToService() {
        val intent = Intent(this, NearbyService::class.java)
        startService(intent)
        bindService(intent, serviceConnection, BIND_AUTO_CREATE)
    }

    private fun sendMessage(text: String) {
        val messageId = UUID.randomUUID().toString().substring(0, 8)
        val ts = System.currentTimeMillis()
        
        nearbyService?.let { service ->
            val message = Message(messageId, text, username ?: Build.MODEL, ts)
            service.messages.add(message)
            service.messages.sortBy { it.timestamp }
            adapter.notifyDataSetChanged()
            findViewById<RecyclerView>(R.id.messagesRecyclerView).scrollToPosition(service.messages.size - 1)
            messageEditText.text?.clear()
            
            triggerHapticFeedback()

            val json = JSONObject().apply {
                put("type", "MSG")
                put("id", messageId)
                put("text", text)
                put("sender", username)
                put("ts", ts)
            }
            service.broadcastPayload(Payload.fromBytes(json.toString().toByteArray(Charsets.UTF_8)))
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

    private fun exitChatRoom() {
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        val intent = Intent(this, NearbyService::class.java)
        stopService(intent)
        finish()
    }

    override fun onMessagesUpdated() {
        runOnUiThread {
            nearbyService?.let {
                adapter.notifyDataSetChanged()
                findViewById<RecyclerView>(R.id.messagesRecyclerView).scrollToPosition(it.messages.size - 1)
            }
        }
    }

    override fun onEndpointsUpdated() {
        runOnUiThread { updateUIState() }
    }

    override fun onHostPromoted() {
        runOnUiThread {
            Toast.makeText(this, "You have been promoted to host", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onRoomRenamed(newName: String) {
        runOnUiThread {
            toolbarTitle.text = newName
        }
    }

    private fun updateUIState() {
        nearbyService?.let {
            val noParticipants = it.connectedEndpoints.isEmpty()
            inputContainer.visibility = if (noParticipants) View.GONE else View.VISIBLE
            noParticipantsOverlay.visibility = if (noParticipants) View.VISIBLE else View.GONE
            participantsAdapter.updateParticipants(it.endpointNames.values.toList())
        }
    }

    override fun onStart() {
        super.onStart()
        nearbyService?.setListener(this)
    }

    override fun onStop() {
        super.onStop()
        nearbyService?.setListener(null)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            bindToService()
        }
    }

    inner class ParticipantsAdapter : RecyclerView.Adapter<ParticipantsAdapter.ViewHolder>() {
        private var participantsList = listOf<String>()

        fun updateParticipants(newParticipants: List<String>) {
            participantsList = newParticipants
            notifyDataSetChanged()
        }

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val nameText: TextView = view.findViewById(android.R.id.text1)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(android.R.layout.simple_list_item_1, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.nameText.text = participantsList[position]
            holder.nameText.setTextColor(ContextCompat.getColor(this@ChatActivity, R.color.on_surface))
        }

        override fun getItemCount() = participantsList.size
    }
}
