package com.ludo.voicedictation.asr

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import org.json.JSONObject
import java.security.MessageDigest

/** Audio port IDs are transient. Prefer a hashed address, otherwise a unique type/name pair. */
internal object MicrophoneDevices {
    data class Choice(val device: AudioDeviceInfo, val key: String, val label: String, val unique: Boolean)

    private fun typeLabel(type: Int): String? = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in microphone"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset microphone"
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB microphone"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth headset microphone"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth LE headset microphone"
        else -> null
    }

    fun available(context: Context): List<Choice> {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val candidates = manager.getDevices(AudioManager.GET_DEVICES_INPUTS).mapNotNull { device ->
            val type = typeLabel(device.type) ?: return@mapNotNull null
            val name = try { device.productName?.toString()?.trim().orEmpty() } catch (_: SecurityException) { "" }
            val address = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) device.address.orEmpty() else ""
            } catch (_: SecurityException) { "" }
            // Never store or display hardware addresses. A name fallback cannot distinguish replacements
            // of the same model, and simultaneous duplicates are intentionally not selectable.
            val identity = JSONObject().put("type", device.type).put("name", name).put("address", address).toString()
            val hash = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            val label = if (name.isBlank() || name.equals(type, ignoreCase = true)) type else "$type — $name"
            val fallback = if (address.isEmpty() && device.type != AudioDeviceInfo.TYPE_BUILTIN_MIC) " (identified by type and name)" else ""
            Triple(device, hash, label + fallback)
        }
        val counts = candidates.groupingBy { it.second }.eachCount()
        return candidates.map { (device, key, label) -> Choice(device, key, label, counts[key] == 1) }
    }

    fun resolve(context: Context, key: String): AudioDeviceInfo {
        val matches = available(context).filter { it.key == key && it.unique }
        check(matches.size == 1) {
            "The selected microphone is unavailable or ambiguous. Reconnect it or choose another microphone."
        }
        return matches.single().device
    }
}
