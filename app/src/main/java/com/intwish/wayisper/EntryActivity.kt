package com.intwish.wayisper

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*

class EntryActivity : AppCompatActivity() {

    private val TAG = "WayIsPerEntry"
    private val STRATEGY = Strategy.P2P_CLUSTER
    private val SERVICE_ID = "com.intwish.wayisper.CHAT"

    private lateinit var roomsRecyclerView: RecyclerView
    private lateinit var roomAdapter: RoomAdapter
    private val availableRooms = mutableListOf<RoomInfo>()

    data class RoomInfo(val endpointId: String, val name: String, val hasPassword: Boolean)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_entry)

        val usernameEditText = findViewById<EditText>(R.id.usernameEditText)
        val createRoomButton = findViewById<Button>(R.id.createRoomButton)

        roomsRecyclerView = findViewById(R.id.roomsRecyclerView)
        roomsRecyclerView.layoutManager = LinearLayoutManager(this)
        roomAdapter = RoomAdapter(availableRooms) { room ->
            val usernameInput = usernameEditText.text.toString().trim()
            val username = if (usernameInput.isEmpty()) Build.MODEL else usernameInput
            joinRoom(room, username)
        }
        roomsRecyclerView.adapter = roomAdapter

        createRoomButton.setOnClickListener {
            val usernameInput = usernameEditText.text.toString().trim()
            val username = if (usernameInput.isEmpty()) Build.MODEL else usernameInput
            showCreateRoomDialog(username)
        }

        checkPermissionsAndStartDiscovery()
    }

    private fun showCreateRoomDialog(username: String) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_create_room, null)
        val roomNameInput = dialogView.findViewById<EditText>(R.id.roomNameInput)
        val passwordInput = dialogView.findViewById<EditText>(R.id.passwordInput)

        AlertDialog.Builder(this)
            .setTitle("Create Chatroom")
            .setView(dialogView)
            .setPositiveButton("Create") { _, _ ->
                val roomName = roomNameInput.text.toString().trim()
                val password = passwordInput.text.toString().trim()
                if (roomName.isNotEmpty()) {
                    saveUsername(username)
                    val intent = Intent(this, ChatActivity::class.java).apply {
                        putExtra("isHost", true)
                        putExtra("roomName", roomName)
                        putExtra("password", password)
                    }
                    startActivity(intent)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun joinRoom(room: RoomInfo, username: String) {
        if (room.hasPassword) {
            val passwordInput = EditText(this)
            AlertDialog.Builder(this)
                .setTitle("Enter Password")
                .setView(passwordInput)
                .setPositiveButton("Join") { _, _ ->
                    val password = passwordInput.text.toString().trim()
                    saveUsername(username)
                    val intent = Intent(this, ChatActivity::class.java).apply {
                        putExtra("isHost", false)
                        putExtra("endpointId", room.endpointId)
                        putExtra("roomName", room.name)
                        putExtra("password", password)
                    }
                    startActivity(intent)
                }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            saveUsername(username)
            val intent = Intent(this, ChatActivity::class.java).apply {
                putExtra("isHost", false)
                putExtra("endpointId", room.endpointId)
                putExtra("roomName", room.name)
            }
            startActivity(intent)
        }
    }

    private fun saveUsername(username: String) {
        val sharedPref = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        sharedPref.edit().putString("username", username).apply()
    }

    private fun checkPermissionsAndStartDiscovery() {
        val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }

        val missing = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 101)
        } else {
            startDiscovery()
        }
    }

    private fun startDiscovery() {
        Nearby.getConnectionsClient(this).startDiscovery(
            SERVICE_ID,
            object : EndpointDiscoveryCallback() {
                override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
                    val nameParts = info.endpointName.split("|")
                    if (nameParts.size >= 2) {
                        val roomName = nameParts[0]
                        val hasPassword = nameParts[1] == "true"
                        availableRooms.add(RoomInfo(endpointId, roomName, hasPassword))
                        roomAdapter.notifyDataSetChanged()
                    }
                }

                override fun onEndpointLost(endpointId: String) {
                    availableRooms.removeAll { it.endpointId == endpointId }
                    roomAdapter.notifyDataSetChanged()
                }
            },
            DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        Nearby.getConnectionsClient(this).stopDiscovery()
    }

    inner class RoomAdapter(
        private val rooms: List<RoomInfo>,
        private val onClick: (RoomInfo) -> Unit
    ) : RecyclerView.Adapter<RoomAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val nameText: TextView = view.findViewById(android.R.id.text1)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(android.R.layout.simple_list_item_1, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val room = rooms[position]
            holder.nameText.text = if (room.hasPassword) "${room.name} (Protected)" else room.name
            holder.itemView.setOnClickListener { onClick(room) }
        }

        override fun getItemCount() = rooms.size
    }
}
