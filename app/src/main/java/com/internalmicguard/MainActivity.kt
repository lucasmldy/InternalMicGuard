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
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

enum class ShizukuState(val label: String) {
    NOT_INSTALLED("Shizuku não instalado"),
    STOPPED("Shizuku instalado, mas parado"),
    PERMISSION_PENDING("Permissão do Shizuku pendente"),
    GRANTED("Shizuku pronto (permissão concedida)")
}

class MainActivity : ComponentActivity() {

    companion object {
        private const val SHIZUKU_REQUEST = 1001
        private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        private val GREEN = Color(0xFF2E7D32)
        private val RED = Color(0xFFC62828)
    }

    private lateinit var router: AudioRouter
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
        try {
            HiddenApiBypass.addHiddenApiExemptions("")
            log("HiddenApiBypass: isenções aplicadas")
        } catch (e: Throwable) {
            log("HiddenApiBypass ERRO: ${e.javaClass.simpleName}: ${e.message}")
        }

        audioManager = getSystemService(AudioManager::class.java)
        router = AudioRouter(this)
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
            selectedMicId = builtinMics.firstOrNull()?.id ?: -1
        }
    }

    private fun isShizukuInstalled(): Boolean =
        try {
            packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }

    private fun updateShizukuState() {
        val previous = shizukuState
        shizukuState = when {
            !Shizuku.pingBinder() -> if (isShizukuInstalled()) ShizukuState.STOPPED else ShizukuState.NOT_INSTALLED
            Shizuku.isPreV11() -> ShizukuState.STOPPED
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> ShizukuState.GRANTED
            else -> ShizukuState.PERMISSION_PENDING
        }
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
        } else {
            Shizuku.requestPermission(SHIZUKU_REQUEST)
        }
    }

    private fun activateGuard() {
        val mic = builtinMics.firstOrNull { it.id == selectedMicId } ?: run {
            log("Nenhum TYPE_BUILTIN_MIC selecionado")
            return
        }
        val hs = headset
        val block = blockHeadset
        busy = true
        thread(name = "RouterEnable") {
            val result = router.enable(mic, hs, block)
            mainHandler.post {
                result.forEach { log(it) }
                guardActive = true
                busy = false
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
        appendLine("=== InternalMicGuard ===")
        appendLine("Dispositivo: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) build=${Build.DISPLAY}")
        appendLine("Shizuku: ${shizukuState.label}")
        appendLine("Guard ativo: $guardActive | Bloquear P2: $blockHeadset")
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
            ShizukuCard()
            DevicesCard()
            GuardCard()
            TestCard()
            LogCard()
        }
    }

    @Composable
    private fun ShizukuCard() {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Shizuku", fontWeight = FontWeight.Bold)
                Text(
                    shizukuState.label,
                    color = if (shizukuState == ShizukuState.GRANTED) GREEN else RED
                )
                when (shizukuState) {
                    ShizukuState.NOT_INSTALLED -> Text("Instale o Shizuku e inicie-o via depuração sem fio.")
                    ShizukuState.STOPPED -> OutlinedButton(onClick = { updateShizukuState() }) {
                        Text("Verificar novamente")
                    }
                    ShizukuState.PERMISSION_PENDING -> Button(onClick = { requestShizukuPermission() }) {
                        Text("Conceder permissão")
                    }
                    ShizukuState.GRANTED -> Unit
                }
            }
        }
    }

    @Composable
    private fun DevicesCard() {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Microfone interno (TYPE_BUILTIN_MIC)", fontWeight = FontWeight.Bold)
                if (builtinMics.isEmpty()) Text("Nenhum mic interno encontrado", color = RED)
                builtinMics.forEach { mic ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = mic.id == selectedMicId,
                            onClick = { selectedMicId = mic.id }
                        )
                        Text(
                            "#${mic.id} ${mic.productName} address='${mic.address}'",
                            fontSize = 13.sp
                        )
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Text("Headset externo", fontWeight = FontWeight.Bold)
                val hs = headset
                Text(
                    hs?.let { "${AudioRouter.typeName(it.type)} #${it.id} ${it.productName} address='${it.address}'" }
                        ?: "Nenhum TYPE_WIRED_HEADSET / TYPE_USB_HEADSET conectado",
                    fontSize = 13.sp
                )
            }
        }
    }

    @Composable
    private fun GuardCard() {
        val ready = shizukuState == ShizukuState.GRANTED && !busy
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Roteamento", fontWeight = FontWeight.Bold)
                Text(
                    if (guardActive) "Mic interno forçado" else "Inativo",
                    color = if (guardActive) GREEN else Color.Unspecified
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = blockHeadset, onCheckedChange = { blockHeadset = it })
                    Text("  Bloquear mic do P2")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { activateGuard() },
                        enabled = ready && selectedMicId != -1
                    ) { Text("Ativar") }
                    OutlinedButton(onClick = { deactivateGuard() }, enabled = ready) {
                        Text("Desativar")
                    }
                }
            }
        }
    }

    @Composable
    private fun TestCard() {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Teste ao vivo", fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        MediaRecorder.AudioSource.MIC to "MIC",
                        MediaRecorder.AudioSource.VOICE_COMMUNICATION to "VOICE_COMMUNICATION"
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
        }
    }

    @Composable
    private fun LogCard() {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
    }
}
