package com.internalmicguard

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.IBinder
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

class AudioRouter(context: Context) {

    companion object {
        const val ROLE_INPUT = 1
        const val DEVICE_ROLE_PREFERRED = 1
        const val DEVICE_ROLE_DISABLED = 2
        private const val SET = "setDevicesRoleForCapturePreset"
        private const val CLEAR = "clearDevicesRoleForCapturePreset"
        private const val SHELL_PACKAGE = "com.android.shell"

        val PRESETS = linkedMapOf(
            MediaRecorder.AudioSource.MIC to "MIC",
            MediaRecorder.AudioSource.CAMCORDER to "CAMCORDER",
            MediaRecorder.AudioSource.VOICE_RECOGNITION to "VOICE_RECOGNITION",
            MediaRecorder.AudioSource.VOICE_COMMUNICATION to "VOICE_COMMUNICATION",
            MediaRecorder.AudioSource.UNPROCESSED to "UNPROCESSED",
            MediaRecorder.AudioSource.VOICE_PERFORMANCE to "VOICE_PERFORMANCE"
        )

        fun typeName(type: Int): String = when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "TYPE_BUILTIN_MIC"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "TYPE_WIRED_HEADSET"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "TYPE_USB_HEADSET"
            AudioDeviceInfo.TYPE_USB_DEVICE -> "TYPE_USB_DEVICE"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "TYPE_BLUETOOTH_SCO"
            AudioDeviceInfo.TYPE_BLE_HEADSET -> "TYPE_BLE_HEADSET"
            AudioDeviceInfo.TYPE_TELEPHONY -> "TYPE_TELEPHONY"
            AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "TYPE_REMOTE_SUBMIX"
            AudioDeviceInfo.TYPE_FM_TUNER -> "TYPE_FM_TUNER"
            AudioDeviceInfo.TYPE_ECHO_REFERENCE -> "TYPE_ECHO_REFERENCE"
            else -> "TYPE_$type"
        }

        fun describe(d: AudioDeviceInfo): String =
            "id=${d.id} ${typeName(d.type)}(${d.type}) product='${d.productName}' address='${d.address}'"
    }

    private val audioManager = context.getSystemService(AudioManager::class.java)

    var signatures: List<String> = emptyList()
        private set

    fun inputs(): List<AudioDeviceInfo> =
        audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()

    fun builtinMics(): List<AudioDeviceInfo> =
        inputs().filter { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }

    fun externalHeadset(): AudioDeviceInfo? =
        inputs().firstOrNull { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET }
            ?: inputs().firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_HEADSET }

    private fun audioService(): Any {
        val binder = SystemServiceHelper.getSystemService(Context.AUDIO_SERVICE)
            ?: throw IllegalStateException("binder do serviço 'audio' indisponível")
        val stub = Class.forName("android.media.IAudioService\$Stub")
        return stub.getMethod("asInterface", IBinder::class.java)
            .invoke(null, ShizukuBinderWrapper(binder))
            ?: throw IllegalStateException("IAudioService.Stub.asInterface retornou null")
    }

    private fun signature(m: Method): String =
        "${m.returnType.simpleName} ${m.name}(${m.parameterTypes.joinToString { it.simpleName }})"

    fun inspect(): List<String> {
        val svc = audioService()
        signatures = svc.javaClass.methods
            .filter { it.name == SET || it.name == CLEAR }
            .map { signature(it) }
            .distinct()
            .ifEmpty { listOf("nenhuma assinatura $SET/$CLEAR encontrada em ${svc.javaClass.name}") }
        return signatures
    }

    private fun attributes(type: Int, address: String): Any {
        val cls = Class.forName("android.media.AudioDeviceAttributes")
        val ctor = cls.getConstructor(
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            String::class.java
        )
        return ctor.newInstance(ROLE_INPUT, type, address)
    }

    private fun buildArgs(m: Method, ints: IntArray, devices: List<Any>?): Array<Any?>? {
        var next = 0
        val args = arrayOfNulls<Any>(m.parameterTypes.size)
        m.parameterTypes.forEachIndexed { idx, p ->
            args[idx] = when {
                p == Int::class.javaPrimitiveType -> if (next < ints.size) ints[next++] else return null
                List::class.java.isAssignableFrom(p) -> devices ?: return null
                p == String::class.java -> SHELL_PACKAGE
                else -> return null
            }
        }
        return if (next == ints.size) args else null
    }

    private fun call(svc: Any, name: String, ints: IntArray, devices: List<Any>?): String {
        val candidates = svc.javaClass.methods.filter { it.name == name }
        if (candidates.isEmpty()) throw NoSuchMethodException("$name ausente em ${svc.javaClass.name}")
        var lastError: Throwable? = null
        for (m in candidates) {
            val args = buildArgs(m, ints, devices) ?: continue
            try {
                val result = m.invoke(svc, *args)
                val status = when (result) {
                    null -> "void"
                    0 -> "SUCCESS(0)"
                    else -> "status=$result"
                }
                return "$status via ${signature(m)}"
            } catch (e: InvocationTargetException) {
                lastError = e.targetException ?: e
            } catch (e: Throwable) {
                lastError = e
            }
        }
        throw lastError ?: NoSuchMethodException("nenhuma assinatura compatível para $name")
    }

    private fun step(label: String, block: () -> String): String =
        try {
            "[$label] ${block()}"
        } catch (e: Throwable) {
            val cause = if (e is InvocationTargetException) e.targetException ?: e else e
            "[$label] ERRO ${cause.javaClass.simpleName}: ${cause.message}"
        }

    fun enable(mic: AudioDeviceInfo, headset: AudioDeviceInfo?, blockHeadset: Boolean): List<String> {
        val out = mutableListOf<String>()
        val svc = try {
            audioService()
        } catch (e: Throwable) {
            return listOf("ERRO ao obter IAudioService: ${e.javaClass.simpleName}: ${e.message}")
        }
        val micAttr = try {
            attributes(mic.type, mic.address)
        } catch (e: Throwable) {
            return listOf("ERRO ao criar AudioDeviceAttributes do mic: ${e.javaClass.simpleName}: ${e.message}")
        }
        val headsetAttr = if (blockHeadset && headset != null) {
            try {
                attributes(headset.type, headset.address)
            } catch (e: Throwable) {
                out += "ERRO ao criar AudioDeviceAttributes do P2: ${e.javaClass.simpleName}: ${e.message}"
                null
            }
        } else null

        out += "ATIVAR mic=${describe(mic)}"
        if (blockHeadset) out += "BLOQUEAR P2=${headset?.let { describe(it) } ?: "nenhum headset conectado"}"

        for ((preset, name) in PRESETS) {
            try {
                out += step("$name($preset) clear PREFERRED") {
                    call(svc, CLEAR, intArrayOf(preset, DEVICE_ROLE_PREFERRED), null)
                }
                out += step("$name($preset) clear DISABLED") {
                    call(svc, CLEAR, intArrayOf(preset, DEVICE_ROLE_DISABLED), null)
                }
                out += step("$name($preset) set PREFERRED") {
                    call(svc, SET, intArrayOf(preset, DEVICE_ROLE_PREFERRED), listOf(micAttr))
                }
                if (headsetAttr != null) {
                    out += step("$name($preset) set DISABLED") {
                        call(svc, SET, intArrayOf(preset, DEVICE_ROLE_DISABLED), listOf(headsetAttr))
                    }
                }
            } catch (e: Throwable) {
                out += "[$name($preset)] ERRO ${e.javaClass.simpleName}: ${e.message}"
            }
        }
        return out
    }

    fun disable(): List<String> {
        val out = mutableListOf<String>()
        val svc = try {
            audioService()
        } catch (e: Throwable) {
            return listOf("ERRO ao obter IAudioService: ${e.javaClass.simpleName}: ${e.message}")
        }
        out += "DESATIVAR"
        for ((preset, name) in PRESETS) {
            try {
                out += step("$name($preset) clear PREFERRED") {
                    call(svc, CLEAR, intArrayOf(preset, DEVICE_ROLE_PREFERRED), null)
                }
                out += step("$name($preset) clear DISABLED") {
                    call(svc, CLEAR, intArrayOf(preset, DEVICE_ROLE_DISABLED), null)
                }
            } catch (e: Throwable) {
                out += "[$name($preset)] ERRO ${e.javaClass.simpleName}: ${e.message}"
            }
        }
        return out
    }
}
