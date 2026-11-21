package com.internalmicguard

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import rikka.shizuku.Shizuku
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {

    companion object {
        private const val SHIZUKU_REQUEST = 1001
        private val GREEN = Color(0xFF2E7D32)
        private val RED = Color(0xFFC62828)
    }

    private lateinit var router: AudioRouter
    private lateinit var prefs: GuardPrefs
    private val tester = MicTester()
    private lateinit var audioManager: AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var shizukuState by mutableStateOf(ShizukuState.STOPPED)
    private var builtinMics by mutableStateOf<List<AudioDeviceInfo>>(emptyList())
    private var headset by mutableStateOf<AudioDeviceInfo?>(null)
    private var selectedMicId by mutableIntStateOf(-1)
    private var blockHeadset by mutableStateOf(false)
    private var guardActive by mutableStateOf(false)
    private var busy by mutableStateOf(false)
    private var diagnosticsExpanded by mutableStateOf(false)
    private var testSource by mutableIntStateOf(MediaRecorder.AudioSource.MIC)
    private var testing by mutableStateOf(false)
    private var levelDb by mutableStateOf(MicTester.MIN_DB)
    private var routedDevice by mutableStateOf<AudioDeviceInfo?>(null)
    private val logLines = mutableStateListOf<String>()

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) {
            addedDevices.filter { it.isSource }.forEach { log("+ entrada ${AudioRouter.describe(it)}") }
            refreshDevices()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) {
            removedDevices.filter { it.isSource }.forEach { log("- entrada ${AudioRouter.describe(it)}") }
            refreshDevices()
        }
    }

    private val binderReceived = Shizuku.OnBinderReceivedListener { updateShizukuState() }
    private val binderDead = Shizuku.OnBinderDeadListener { updateShizukuState() }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == SHIZUKU_REQUEST) {
            log("Shizuku permissão: ${if (grantResult == PackageManager.PERMISSION_GRANTED) "concedida" else "negada"}")
            updateShizukuState()
        }
    }

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startTest() else log("RECORD_AUDIO negado")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        audioManager = getSystemService(AudioManager::class.java)
        prefs = GuardPrefs(this)
        router = AudioRouter(this)
        log(AudioRouter.hiddenApiStatus)
        blockHeadset = prefs.blockHeadset
        guardActive = prefs.guardActive
        refreshDevices()
        audioManager.registerAudioDeviceCallback(deviceCallback, mainHandler)

        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
        updateShizukuState()

        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Screen()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        guardActive = prefs.guardActive
        blockHeadset = prefs.blockHeadset
        refreshDevices()
        updateShizukuState()
    }

    override fun onDestroy() {
        tester.stop()
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeBinderDeadListener(binderDead)
        Shizuku.removeRequestPermissionResultListener(permissionResult)
        super.onDestroy()
    }

    private fun log(line: String) {
        val entry = "${timeFormat.format(Date())} $line"
        if (Looper.myLooper() == Looper.getMainLooper()) logLines.add(entry) else mainHandler.post { logLines.add(entry) }
    }

    private fun refreshDevices() {
        builtinMics = router.builtinMics()
        headset = router.externalHeadset()
        if (builtinMics.none { it.id == selectedMicId }) {
            selectedMicId = router.resolveMic(prefs.micAddress)?.id ?: -1
        }
    }

    private fun selectMic(mic: AudioDeviceInfo) {
        selectedMicId = mic.id
        prefs.micAddress = mic.address
        if (guardActive) activateGuard()
    }

    private fun setBlockHeadset(value: Boolean) {
        blockHeadset = value
        prefs.blockHeadset = value
        if (guardActive) activateGuard()
    }

    private fun updateShizukuState() {
        val previous = shizukuState
        shizukuState = shizukuState(this)
        if (previous != shizukuState) log("Shizuku: ${shizukuState.label}")
        if (shizukuState == ShizukuState.GRANTED && router.signatures.isEmpty()) {
            try {
                router.inspect().forEach { log("IAudioService: $it") }
            } catch (e: Throwable) {
                log("IAudioService inspeção ERRO: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    private fun requestShizukuPermission() {
        if (!Shizuku.pingBinder()) {
            updateShizukuState()
            return
        }
        if (Shizuku.shouldShowRequestPermissionRationale()) {
            log("Shizuku: permissão negada permanentemente; libere no app Shizuku")
            Toast.makeText(this, "Libere a permissão no app Shizuku", Toast.LENGTH_SHORT).show()
        } else {
            Shizuku.requestPermission(SHIZUKU_REQUEST)
        }
    }

    private fun activateGuard() {
        val mic = builtinMics.firstOrNull { it.id == selectedMicId } ?: run {
            log("Nenhum TYPE_BUILTIN_MIC selecionado")
            return
        }
        prefs.micAddress = mic.address
        val hs = headset
        val block = blockHeadset
        busy = true
        thread(name = "RouterEnable") {
            val result = router.enable(mic, hs, block)
            val ok = AudioRouter.enableSucceeded(result)
            mainHandler.post {
                result.forEach { log(it) }
                guardActive = ok
                prefs.guardActive = ok
                busy = false
                if (!ok) Toast.makeText(this, "Falha ao aplicar roteamento (veja Diagnóstico)", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun deactivateGuard() {
        busy = true
        thread(name = "RouterDisable") {
            val result = router.disable()
            mainHandler.post {
                result.forEach { log(it) }
                guardActive = false
                prefs.guardActive = false
                busy = false
            }
        }
    }

    private fun toggleTest() {
        if (testing) {
            tester.stop()
            testing = false
            levelDb = MicTester.MIN_DB
            log("Teste parado")
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        startTest()
    }

    private fun startTest() {
        val name = AudioRouter.PRESETS[testSource] ?: testSource.toString()
        val ok = tester.start(
            testSource,
            onUpdate = { db, device ->
                levelDb = db
                if (device?.id != routedDevice?.id) {
                    log("routedDevice: ${device?.let { AudioRouter.describe(it) } ?: "null"}")
                }
                routedDevice = device
            },
            onError = {
                log("Teste ERRO: $it")
                testing = false
                levelDb = MicTester.MIN_DB
            }
        )
        if (ok) {
            testing = true
            routedDevice = null
            log("Teste iniciado source=$name($testSource)")
        }
    }

    private fun buildReport(): String = buildString {
        appendLine("=== InternalMicGuard ${BuildConfigInfo.versionName(this@MainActivity)} ===")
        appendLine("Dispositivo: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) build=${Build.DISPLAY}")
        appendLine("Shizuku: ${shizukuState.label}")
        appendLine("Guard ativo: $guardActive | Bloquear P2: $blockHeadset | mic salvo: '${prefs.micAddress}'")
        appendLine()
        appendLine("--- AudioDeviceInfo (entradas) ---")
        router.inputs().forEach { appendLine(AudioRouter.describe(it)) }
        appendLine()
        appendLine("--- AudioDeviceInfo (saídas) ---")
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).forEach { appendLine(AudioRouter.describe(it)) }
        appendLine()
        appendLine("--- Assinaturas IAudioService ---")
        if (router.signatures.isEmpty()) appendLine("(não inspecionado)") else router.signatures.forEach { appendLine(it) }
        appendLine()
        appendLine("--- Último routedDevice ---")
        appendLine(routedDevice?.let { AudioRouter.describe(it) } ?: "null")
        appendLine()
        appendLine("--- Log ---")
        logLines.forEach { appendLine(it) }
    }

    private fun copyLogs() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("InternalMicGuard logs", buildReport()))
        Toast.makeText(this, "Logs copiados", Toast.LENGTH_SHORT).show()
    }

    @Composable
    private fun Screen() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("InternalMicGuard", style = MaterialTheme.typography.headlineSmall)
            MainCard()
            DiagnosticsCard()
        }
    }

    @Composable
    private fun MainCard() {
        val ready = shizukuState == ShizukuState.GRANTED && !busy
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        shizukuState.label,
                        color = if (shizukuState == ShizukuState.GRANTED) GREEN else RED,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    when (shizukuState) {
                        ShizukuState.PERMISSION_PENDING -> Button(onClick = { requestShizukuPermission() }) {
                            Text("Permitir")
                        }
                        ShizukuState.STOPPED -> OutlinedButton(onClick = { updateShizukuState() }) {
                            Text("Verificar")
                        }
                        else -> Unit
                    }
                }
                if (shizukuState == ShizukuState.NOT_INSTALLED) {
                    Text("Instale o Shizuku e inicie-o via Depuração sem fio.", fontSize = 13.sp)
                }

                val hs = headset
                Text(
                    if (hs != null) "Fone P2: conectado (${hs.productName})" else "Fone P2: não conectado",
                    color = if (hs != null) Color.Unspecified else Color.Gray
                )

                HorizontalDivider()
                Text("Microfone interno", fontWeight = FontWeight.Bold)
                if (builtinMics.isEmpty()) Text("Nenhum mic interno encontrado", color = RED)
                builtinMics.forEach { mic ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !busy) { selectMic(mic) }
                    ) {
                        RadioButton(
                            selected = mic.id == selectedMicId,
                            onClick = { selectMic(mic) },
                            enabled = !busy
                        )
                        Text(
                            if (mic.address.isBlank()) "#${mic.id} ${mic.productName}" else mic.address,
                            fontSize = 14.sp
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Bloquear mic do P2", modifier = Modifier.weight(1f))
                    Switch(checked = blockHeadset, onCheckedChange = { setBlockHeadset(it) }, enabled = !busy)
                }

                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Forçar mic interno", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text(
                            when {
                                busy -> "Aplicando..."
                                guardActive -> "Ativo"
                                else -> "Inativo"
                            },
                            color = if (guardActive) GREEN else Color.Gray
                        )
                    }
                    Switch(
                        checked = guardActive,
                        onCheckedChange = { if (it) activateGuard() else deactivateGuard() },
                        enabled = ready && selectedMicId != -1
                    )
                }
            }
        }
    }

    @Composable
    private fun DiagnosticsCard() {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { diagnosticsExpanded = !diagnosticsExpanded }
                ) {
                    Text("Diagnóstico", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text(if (diagnosticsExpanded) "▲" else "▼")
                }
                if (diagnosticsExpanded) {
                    TestSection()
                    HorizontalDivider()
                    LogSection()
                }
            }
        }
    }

    @Composable
    private fun TestSection() {
        Text("Teste de captação ao vivo", fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                MediaRecorder.AudioSource.MIC to "MIC",
                MediaRecorder.AudioSource.VOICE_COMMUNICATION to "VOICE_COMM"
            ).forEach { (source, label) ->
                FilterChip(
                    selected = testSource == source,
                    onClick = { if (!testing) testSource = source },
                    enabled = !testing,
                    label = { Text(label) }
                )
            }
        }
        Button(onClick = { toggleTest() }) {
            Text(if (testing) "Parar Teste" else "Iniciar Teste")
        }
        val fraction = ((levelDb - MicTester.MIN_DB) / -MicTester.MIN_DB).coerceIn(0f, 1f)
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier
                .fillMaxWidth()
                .height(14.dp),
            color = when {
                levelDb > -6f -> RED
                levelDb > -20f -> Color(0xFFF9A825)
                else -> GREEN
            }
        )
        Text(String.format(Locale.US, "%.1f dBFS", levelDb), fontFamily = FontFamily.Monospace)
        val dev = routedDevice
        val color = when (dev?.type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> GREEN
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> RED
            else -> Color.Unspecified
        }
        Text("routedDevice", fontWeight = FontWeight.Bold)
        if (dev == null) {
            Text(if (testing) "aguardando..." else "-", fontFamily = FontFamily.Monospace)
        } else {
            Text(
                "type: ${AudioRouter.typeName(dev.type)} (${dev.type})\n" +
                    "productName: ${dev.productName}\n" +
                    "address: '${dev.address}'\n" +
                    "id: ${dev.id}",
                color = color,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }
    }

    @Composable
    private fun LogSection() {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Log", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { logLines.clear() }) { Text("Limpar") }
            Text(" ")
            Button(onClick = { copyLogs() }) { Text("Copiar Logs") }
        }
        Text(
            logLines.takeLast(60).joinToString("\n"),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 14.sp
        )
    }
}

private object BuildConfigInfo {
    fun versionName(activity: ComponentActivity): String =
        try {
            activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: "?"
        } catch (e: Exception) {
            "?"
        }
}
