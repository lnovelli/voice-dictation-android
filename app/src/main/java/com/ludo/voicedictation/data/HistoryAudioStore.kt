package com.ludo.voicedictation.data

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.roundToInt

/** Private, no-backup storage for original 16 kHz mono PCM recordings linked from history. */
object HistoryAudioStore {
    private const val TAG = "HistoryAudioStore"
    private const val DIRECTORY_NAME = "history-audio"
    private const val SAMPLE_RATE = 16_000
    private const val MAX_DURATION_SECONDS = 120
    private const val MAX_SAMPLES = SAMPLE_RATE * MAX_DURATION_SECONDS
    private const val WAV_HEADER_BYTES = 44
    private const val MAX_WAV_BYTES = WAV_HEADER_BYTES.toLong() + MAX_SAMPLES * 2L
    private val wavNamePattern = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.wav$")
    private val tempNamePattern = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.wav\\.tmp$")

    fun isSafeFileName(value: String): Boolean = wavNamePattern.matches(value)

    /** Saves the complete, untrimmed recording as a standard WAV; null means storage failed. */
    fun save(context: Context, samples: FloatArray): String? {
        if (samples.isEmpty() || samples.size > MAX_SAMPLES) return null
        var temporary: File? = null
        return try {
            val directory = audioDirectory(context)
            if (!directory.isDirectory && !directory.mkdirs()) return null
            val name = "${UUID.randomUUID()}.wav"
            val destination = File(directory, name)
            val temporaryFile = File(directory, "$name.tmp")
            temporary = temporaryFile
            val pcmBytes = samples.size * 2
            FileOutputStream(temporaryFile).use { output ->
                writeWavHeader(output, pcmBytes)
                val buffer = ByteArray(16 * 1024)
                var offset = 0
                while (offset < samples.size) {
                    val sampleCount = minOf(buffer.size / 2, samples.size - offset)
                    for (i in 0 until sampleCount) {
                        val sample = samples[offset + i]
                        val finite = if (sample.isFinite()) sample else 0f
                        val clipped = finite.coerceIn(-1f, Short.MAX_VALUE / 32768f)
                        val value = (clipped * 32768f).roundToInt()
                            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                        buffer[i * 2] = (value and 0xff).toByte()
                        buffer[i * 2 + 1] = (value shr 8).toByte()
                    }
                    output.write(buffer, 0, sampleCount * 2)
                    offset += sampleCount
                }
                output.fd.sync()
            }
            if (destination.exists() || !temporary.renameTo(destination)) null else name
        } catch (e: Exception) {
            Log.w(TAG, "Could not save history audio (${e.javaClass.simpleName}).")
            null
        } finally {
            temporary?.takeIf { it.exists() }?.delete()
        }
    }

    /** Resolves only generated WAV names and rejects traversal, symlinks outside the directory, and oversized files. */
    fun find(context: Context, fileName: String?): File? {
        if (fileName == null || !isSafeFileName(fileName)) return null
        return runCatching {
            val directory = audioDirectory(context).canonicalFile
            val file = File(directory, fileName).canonicalFile
            if (file.parentFile != directory || !file.isFile || file.length() !in WAV_HEADER_BYTES.toLong()..MAX_WAV_BYTES) {
                null
            } else file
        }.getOrNull()
    }

    fun delete(context: Context, fileName: String?) {
        if (fileName == null || !isSafeFileName(fileName)) return
        runCatching { File(audioDirectory(context), fileName).delete() }
    }

    /** Removes crash leftovers and files no longer referenced by a history record. Call at process start. */
    fun retain(context: Context, referencedFileNames: Collection<String?>) {
        val directory = audioDirectory(context)
        if (!directory.isDirectory) return
        val retained = referencedFileNames.filterNotNull().filter(::isSafeFileName).toSet()
        directory.listFiles()?.forEach { file ->
            when {
                tempNamePattern.matches(file.name) -> file.delete()
                isSafeFileName(file.name) && file.name !in retained -> file.delete()
            }
        }
    }

    private fun audioDirectory(context: Context): File = File(context.noBackupFilesDir, DIRECTORY_NAME)

    private fun writeWavHeader(output: FileOutputStream, pcmBytes: Int) {
        writeAscii(output, "RIFF")
        writeIntLittleEndian(output, 36 + pcmBytes)
        writeAscii(output, "WAVE")
        writeAscii(output, "fmt ")
        writeIntLittleEndian(output, 16)
        writeShortLittleEndian(output, 1)
        writeShortLittleEndian(output, 1)
        writeIntLittleEndian(output, SAMPLE_RATE)
        writeIntLittleEndian(output, SAMPLE_RATE * 2)
        writeShortLittleEndian(output, 2)
        writeShortLittleEndian(output, 16)
        writeAscii(output, "data")
        writeIntLittleEndian(output, pcmBytes)
    }

    private fun writeAscii(output: FileOutputStream, value: String) {
        output.write(value.toByteArray(Charsets.US_ASCII))
    }

    private fun writeShortLittleEndian(output: FileOutputStream, value: Int) {
        output.write(value and 0xff)
        output.write((value shr 8) and 0xff)
    }

    private fun writeIntLittleEndian(output: FileOutputStream, value: Int) {
        output.write(value and 0xff)
        output.write((value shr 8) and 0xff)
        output.write((value shr 16) and 0xff)
        output.write((value shr 24) and 0xff)
    }
}
