package com.intwish.wayisper

enum class MessageType {
    USER, SYSTEM
}

data class Message(
    val id: String,
    val text: String, 
    val sender: String,
    val timestamp: Long = System.currentTimeMillis(),
    val heardBy: MutableSet<String> = mutableSetOf(),
    val type: MessageType = MessageType.USER
)
