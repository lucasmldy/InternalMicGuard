package com.internalmicguard

import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import rikka.shizuku.Shizuku
import kotlin.concurrent.thread

class GuardTileService : TileService() {

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var busy = false

    private val binderReceived = Shizuku.OnBinderReceivedListener { main.post { refreshTile() } }
    private val binderDead = Shizuku.OnBinderDeadListener { main.post { refreshTile() } }

    override fun onStartListening() {
        super.onStartListening()
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        refreshTile()
    }

    override fun onStopListening() {
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeBinderDeadListener(binderDead)
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        if (busy) return
        val state = shizukuState(this)
        if (state != ShizukuState.GRANTED) {
            Toast.makeText(this, state.label, Toast.LENGTH_SHORT).show()
            refreshTile()
            return
        }
        val prefs = GuardPrefs(this)
        val turnOn = !prefs.guardActive
        busy = true
        qsTile?.let {
            it.subtitle = if (turnOn) "Ativando..." else "Desativando..."
            it.updateTile()
        }
        val appContext = applicationContext
        thread(name = "GuardTile") {
            val message = try {
                val router = AudioRouter(appContext)
                if (turnOn) {
                    val mic = router.resolveMic(prefs.micAddress)
                    if (mic == null) {
                        "Nenhum microfone interno encontrado"
                    } else {
                        val lines = router.enable(mic, router.externalHeadset(), prefs.blockHeadset)
                        if (AudioRouter.enableSucceeded(lines)) {
                            prefs.guardActive = true
                            "Mic interno forçado"
                        } else {
                            "Falha ao aplicar roteamento"
                        }
                    }
                } else {
                    router.disable()
                    prefs.guardActive = false
                    "Roteamento padrão restaurado"
                }
            } catch (e: Throwable) {
                "Erro: ${e.javaClass.simpleName}"
            }
            main.post {
                busy = false
                Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
                refreshTile()
            }
        }
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        val state = shizukuState(this)
        val active = GuardPrefs(this).guardActive
        tile.label = "Mic interno"
        tile.state = when {
            state != ShizukuState.GRANTED -> Tile.STATE_UNAVAILABLE
            active -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.subtitle = when {
            state != ShizukuState.GRANTED -> state.label
            active -> "Ativo"
            else -> "Inativo"
        }
        tile.updateTile()
    }
}
