package com.intwish.wayisper

data class Message(
    val id: String,
    val text: String, 
    val sender: String,
    val timestamp: Long = System.currentTimeMillis(),
    val heardBy: MutableSet<String> = mutableSetOf()
)