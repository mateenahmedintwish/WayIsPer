package com.intwish.wayisper

import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import java.text.SimpleDateFormat
import java.util.*

class MessageAdapter(private val messages: List<Message>, private val currentUsername: String) :
    RecyclerView.Adapter<MessageAdapter.MessageViewHolder>() {

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    class MessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val rootLayout: LinearLayout = view as LinearLayout
        val cardView: MaterialCardView = view.findViewById(R.id.messageCard)
        val senderTextView: TextView = view.findViewById(R.id.senderTextView)
        val messageTextView: TextView = view.findViewById(R.id.messageTextView)
        val heardByTextView: TextView = view.findViewById(R.id.heardByTextView)
        val timestampTextView: TextView = view.findViewById(R.id.timestampTextView)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_message, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        val message = messages[position]
        holder.senderTextView.text = message.sender
        holder.messageTextView.text = message.text
        holder.timestampTextView.text = timeFormat.format(Date(message.timestamp))

        val isMe = message.sender == currentUsername
        
        // Alignment
        val params = holder.cardView.layoutParams as LinearLayout.LayoutParams
        params.gravity = if (isMe) Gravity.END else Gravity.START
        holder.cardView.layoutParams = params
        
        val textParams = holder.heardByTextView.layoutParams as LinearLayout.LayoutParams
        textParams.gravity = if (isMe) Gravity.END else Gravity.START
        holder.heardByTextView.layoutParams = textParams

        // Seen by / Heard by logic
        if (message.heardBy.isNotEmpty()) {
            holder.heardByTextView.visibility = View.VISIBLE
            holder.heardByTextView.text = "Seen by: ${message.heardBy.joinToString(", ")}"
        } else {
            holder.heardByTextView.visibility = View.GONE
        }
        
        holder.cardView.setCardBackgroundColor(
            holder.itemView.context.getColor(if (isMe) R.color.primary_container else R.color.surface_variant)
        )
        holder.messageTextView.setTextColor(
            holder.itemView.context.getColor(if (isMe) R.color.on_primary_container else R.color.on_surface_variant)
        )
    }

    override fun getItemCount() = messages.size
}
