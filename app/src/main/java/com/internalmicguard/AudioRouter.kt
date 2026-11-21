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
        private const val SET_PREFERRED = "setPreferredDevicesForCapturePreset"
        private const val CLEAR_PREFERRED = "clearPreferredDevicesForCapturePreset"
        private const val GET_PREFERRED = "getPreferredDevicesForCapturePreset"
        private const val SHELL_PACKAGE = "com.android.shell"
        private val SIGNATURE_KEYWORDS = listOf("capturepreset", "preferreddevice", "devicesrole")

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
            28 -> "28"
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
            .filter { m -> SIGNATURE_KEYWORDS.any { m.name.lowercase().contains(it) } }
            .map { signature(it) }
            .distinct()
            .sorted()
            .ifEmpty { listOf("nenhum método CapturePreset/PreferredDevice/DevicesRole em ${svc.javaClass.name}") }
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

    private fun formatResult(result: Any?): String = when (result) {
        null -> "void"
        0 -> "SUCCESS(0)"
        is Int -> "status=$result"
        is Boolean -> "result=$result"
        is List<*> -> if (result.isEmpty()) "[]" else result.joinToString(prefix = "[", postfix = "]")
        else -> result.toString()
    }

    private fun invokeMethods(
        svc: Any,
        methods: List<Method>,
        intVariants: List<IntArray>,
        devices: List<Any>?
    ): String? {
        var lastError: Throwable? = null
        for (m in methods) {
            for (ints in intVariants) {
                val args = buildArgs(m, ints, devices) ?: continue
                try {
                    return "${formatResult(m.invoke(svc, *args))} via ${signature(m)}"
                } catch (e: InvocationTargetException) {
                    lastError = e.targetException ?: e
                } catch (e: Throwable) {
                    lastError = e
                }
            }
        }
        if (lastError != null) throw lastError
        return null
    }

    private fun call(svc: Any, name: String, ints: IntArray, devices: List<Any>?): String {
        val candidates = svc.javaClass.methods.filter { it.name == name }
        if (candidates.isEmpty()) return "INFO: $name ausente em ${svc.javaClass.simpleName}"
        return invokeMethods(svc, candidates, listOf(ints), devices)
            ?: "INFO: nenhuma assinatura compatível para $name"
    }

    private fun disabledMethods(svc: Any, prefixes: List<String>): List<Method> =
        svc.javaClass.methods.filter { m ->
            val n = m.name.lowercase()
            n.contains("capturepreset") &&
                (n.contains("disabled") || n.contains("nondefault")) &&
                prefixes.any { n.startsWith(it) }
        }

    private fun step(label: String, block: () -> String): String =
        try {
            "[$label] ${block()}"
        } catch (e: Throwable) {
            val cause = if (e is InvocationTargetException) e.targetException ?: e else e
            "[$label] ERRO ${cause.javaClass.simpleName}: ${cause.message}"
        }

    private fun clearDisabled(svc: Any, preset: Int, headsetAttr: Any?): String {
        val methods = disabledMethods(svc, listOf("clear", "remove"))
        if (methods.isEmpty()) return "INFO: sem método Disabled/NonDefault de limpeza no IAudioService"
        val devices = headsetAttr?.let { listOf(it) }
        return invokeMethods(svc, methods, listOf(intArrayOf(preset), intArrayOf(preset, DEVICE_ROLE_DISABLED)), devices)
            ?: "INFO: métodos ${methods.joinToString { it.name }} exigem argumentos indisponíveis"
    }

    private fun setDisabled(svc: Any, preset: Int, headsetAttr: Any): String {
        val methods = disabledMethods(svc, listOf("set", "add"))
        if (methods.isEmpty()) return "INFO: IAudioService sem método Disabled/NonDefault para CapturePreset; bloqueio do P2 ignorado"
        return invokeMethods(svc, methods, listOf(intArrayOf(preset), intArrayOf(preset, DEVICE_ROLE_DISABLED)), listOf(headsetAttr))
            ?: "INFO: nenhuma assinatura compatível em ${methods.joinToString { it.name }}"
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
        if (blockHeadset) {
            out += "BLOQUEAR P2=${headset?.let { describe(it) } ?: "nenhum headset conectado"}"
            if (disabledMethods(svc, listOf("set", "add")).isEmpty()) {
                out += "INFO: IAudioService não expõe role DISABLED/NonDefault para CapturePreset; apenas PREFERRED será aplicado"
            }
        }

        for ((preset, name) in PRESETS) {
            try {
                out += step("$name($preset) clearPreferred") {
                    call(svc, CLEAR_PREFERRED, intArrayOf(preset), null)
                }
                out += step("$name($preset) setPreferred") {
                    call(svc, SET_PREFERRED, intArrayOf(preset), listOf(micAttr))
                }
                if (headsetAttr != null && disabledMethods(svc, listOf("set", "add")).isNotEmpty()) {
                    out += step("$name($preset) setDisabled") { setDisabled(svc, preset, headsetAttr) }
                }
                out += step("$name($preset) getPreferred") {
                    call(svc, GET_PREFERRED, intArrayOf(preset), null)
                }
            } catch (e: Throwable) {
                out += "[$name($preset)] ERRO ${e.javaClass.simpleName}: ${e.message}"
            }
        }
        return out
    }

    fun disable(headset: AudioDeviceInfo? = externalHeadset()): List<String> {
        val out = mutableListOf<String>()
        val svc = try {
            audioService()
        } catch (e: Throwable) {
            return listOf("ERRO ao obter IAudioService: ${e.javaClass.simpleName}: ${e.message}")
        }
        val headsetAttr = headset?.let { runCatching { attributes(it.type, it.address) }.getOrNull() }
        val hasDisabledClear = disabledMethods(svc, listOf("clear", "remove")).isNotEmpty()
        out += "DESATIVAR"
        if (!hasDisabledClear) out += "INFO: sem método Disabled/NonDefault de limpeza no IAudioService"
        for ((preset, name) in PRESETS) {
            try {
                out += step("$name($preset) clearPreferred") {
                    call(svc, CLEAR_PREFERRED, intArrayOf(preset), null)
                }
                if (hasDisabledClear) {
                    out += step("$name($preset) clearDisabled") { clearDisabled(svc, preset, headsetAttr) }
                }
                out += step("$name($preset) getPreferred") {
                    call(svc, GET_PREFERRED, intArrayOf(preset), null)
                }
            } catch (e: Throwable) {
                out += "[$name($preset)] ERRO ${e.javaClass.simpleName}: ${e.message}"
            }
        }
        return out
    }
}
