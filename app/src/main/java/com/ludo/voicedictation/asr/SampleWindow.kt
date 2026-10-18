package com.ludo.voicedictation.asr

internal class SampleWindow(private val capacity: Int) {
    private val samples = FloatArray(capacity)
    private var head = 0
    private var start = 0L
    var size = 0
        private set

    init { require(capacity > 0) }

    fun append(frame: FloatArray) {
        check(size + frame.size <= capacity) { "Speech segment exceeded the bounded audio buffer." }
        for (sample in frame) {
            samples[(head + size) % capacity] = sample
            size++
        }
    }

    fun discardBefore(position: Long) {
        val count = (position - start).coerceIn(0L, size.toLong()).toInt()
        head = (head + count) % capacity
        start += count
        size -= count
    }

    fun keepLast(count: Int) {
        require(count >= 0)
        discardBefore(start + size - count)
    }

    fun snapshot(): FloatArray = FloatArray(size) { samples[(head + it) % capacity] }
}