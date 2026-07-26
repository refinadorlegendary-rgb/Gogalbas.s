package com.bassboost.global

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.media.audiofx.BassBoost
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Servicio en primer plano que aplica el motor de graves ("lowshelf + punch + limiter",
 * el mismo diseño que corregimos en la versión de navegador) al AUDIO GLOBAL del
 * dispositivo (audioSessionId = 0), no a una sola app. Así afecta a Spotify, YouTube
 * Music, o cualquier reproductor que esté sonando en ese momento.
 *
 * IMPORTANTE — limitaciones reales del sistema, no de este código:
 * - audioSessionId = 0 representa el "mix" de salida global. Crear efectos ahí es una
 *   API pública de Android, pero algunos fabricantes (Samsung con Adapt Sound, por
 *   ejemplo) restringen o ignoran efectos globales de apps de terceros.
 * - Si la app de música usa reproducción "offloaded" (audio comprimido enviado
 *   directo al DSP de hardware, común para ahorrar batería en reproducción en
 *   segundo plano), los efectos de sesión pueden no aplicarse. Esto no tiene
 *   solución desde una app normal sin root.
 * - DynamicsProcessing (la API que replica mejor nuestro diseño lowshelf+punch+
 *   limiter) requiere Android 9 (API 28) o superior. En versiones anteriores se
 *   usa un fallback con BassBoost + Equalizer, que es más limitado pero funcional.
 */
class GlobalBassService : Service() {

    companion object {
        const val CHANNEL_ID = "global_bass_boost_channel"
        const val NOTIF_ID = 1
        const val ACTION_SET_LEVEL = "com.bassboost.global.SET_LEVEL"
        const val EXTRA_LEVEL = "level" // 0..100

        // Diseño portado del motor Web Audio "CarPlayer Pro": 4 zonas en vez de 3.
        // 0-45Hz = sub-grave puro, 45-75Hz = "golpe" profundo, 75-130Hz = zona de
        // "cajoneo" (boxiness) que se recorta un poco para que no suene sucio,
        // 130Hz+ = todo lo demás, siempre plano.
        const val MAX_SUB_BOOST_DB = 10f   // banda 0 (0-45Hz)
        const val MAX_DEEP_BOOST_DB = 8f    // banda 1 (45-75Hz)
        const val THERMAL_GUARD_DB = -3f    // banda 2 (75-130Hz), fija, anti-cajoneo
        const val MAX_INPUT_GAIN_CUT_DB = 3f // compensación de volumen general al máximo
    }

    private var dynamicsProcessing: DynamicsProcessing? = null
    private var bassBoost: BassBoost? = null
    private var equalizer: Equalizer? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var usingDynamicsProcessing = false
    private var currentLevel = 0 // 0..100

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        setupGlobalEffectChain()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SET_LEVEL) {
            val level = intent.getIntExtra(EXTRA_LEVEL, 0)
            applyBassLevel(level)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        releaseEffects()
        super.onDestroy()
    }

    // ---------------------------------------------------------------------
    // Construcción de la cadena de efectos sobre el mix global (sesión 0)
    // ---------------------------------------------------------------------

    private fun setupGlobalEffectChain() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (trySetupDynamicsProcessing()) {
                usingDynamicsProcessing = true
                return
            }
        }
        // Fallback para Android < 9, o si DynamicsProcessing no está disponible
        // en este dispositivo/fabricante.
        trySetupLegacyFallback()
    }

    private fun trySetupDynamicsProcessing(): Boolean {
        return try {
            val channelCount = 2
            // Diseño portado del motor Web Audio de referencia ("CarPlayer Pro"):
            // en vez de 3 zonas, ahora son 4 -> aísla mejor el grave y evita el
            // "cajoneo" (boxiness) que hacía sonar todo más sucio.
            //   Banda 0: 0-45Hz   -> sub-grave puro (boost variable)
            //   Banda 1: 45-75Hz  -> "golpe" profundo (boost variable, menor)
            //   Banda 2: 75-130Hz -> zona de cajoneo, SIEMPRE -3dB fija
            //   Banda 3: 130Hz+   -> resto del espectro, SIEMPRE 0dB, intacto
            val config = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                channelCount,
                true, 4,
                true, 4,
                false, 0,
                true
            ).build()

            val dp = DynamicsProcessing(0, 0, config)

            for (ch in 0 until channelCount) {
                dp.setPreEqBandAllChannelsTo(0, DynamicsProcessing.EqBand(true, 45f, 0f))
                dp.setPreEqBandAllChannelsTo(1, DynamicsProcessing.EqBand(true, 75f, 0f))
                // Banda anti-cajoneo: fija, nunca se mueve con el slider.
                dp.setPreEqBandAllChannelsTo(2, DynamicsProcessing.EqBand(true, 130f, THERMAL_GUARD_DB))
                // Resto del espectro: siempre plano, voces/medios/agudos intactos.
                dp.setPreEqBandAllChannelsTo(3, DynamicsProcessing.EqBand(true, 20000f, 0f))

                // Compresor "anti-tick" solo sobre el sub-grave aislado — igual que
                // en el script web, pero con attack de 10ms en vez de 1ms: Android
                // no tiene el look-ahead interno que sí tiene WebAudio's
                // DynamicsCompressorNode, así que un attack tan rápido aquí sí
                // recorta el propio ciclo de la onda (esto fue lo que ya vimos
                // que causaba distorsión antes). 10ms sigue siendo rápido de sobra
                // para atrapar picos, sin deformar la onda de 45Hz (~22ms/ciclo).
                dp.setMbcBandAllChannelsTo(
                    0,
                    DynamicsProcessing.MbcBand(
                        true, 45f,
                        10f, 150f,   // attack/release (ms)
                        4f, -18f, 8f,
                        0f, 0f, 0f, 0f
                    )
                )
                dp.setMbcBandAllChannelsTo(
                    1,
                    DynamicsProcessing.MbcBand(
                        true, 75f,
                        10f, 150f,
                        3f, -14f, 6f,
                        0f, 0f, 0f, 0f
                    )
                )
                // Banda anti-cajoneo y banda plana: sin compresión, pasan intactas.
                dp.setMbcBandAllChannelsTo(
                    2,
                    DynamicsProcessing.MbcBand(false, 130f, 10f, 100f, 1f, 0f, 0f, 0f, 0f, 0f, 0f)
                )
                dp.setMbcBandAllChannelsTo(
                    3,
                    DynamicsProcessing.MbcBand(false, 20000f, 10f, 100f, 1f, 0f, 0f, 0f, 0f, 0f, 0f)
                )
            }

            // Limitador final sobre la mezcla completa, como red de seguridad.
            val limiter = DynamicsProcessing.Limiter(
                true, true, 1,
                5f, 90f, 14f, -4f, 0f
            )
            for (ch in 0 until channelCount) {
                dp.setLimiterAllChannelsTo(limiter)
            }

            // Compensación de volumen general dinámica (equivalente al
            // "masterInput.gain = 0.35 - v*0.0055" del script web): deja margen
            // (headroom) a medida que se sube el grave, en vez de un volumen fijo.
            dp.setInputGainAllChannelsTo(0f) // se ajusta en applyBassLevel()

            dp.enabled = true
            dynamicsProcessing = dp
            true
        } catch (e: Exception) {
            Log.w("GlobalBassService", "DynamicsProcessing no disponible en este dispositivo: ${e.message}")
            dynamicsProcessing?.release()
            dynamicsProcessing = null
            false
        }
    }

    private fun trySetupLegacyFallback() {
        try {
            bassBoost = BassBoost(0, 0).apply {
                enabled = true
            }
            equalizer = Equalizer(0, 0).apply {
                enabled = true
            }
            loudnessEnhancer = LoudnessEnhancer(0).apply {
                enabled = true
            }
        } catch (e: Exception) {
            Log.e(
                "GlobalBassService",
                "No se pudo crear ningún efecto de audio global en este dispositivo: ${e.message}"
            )
        }
    }

    // ---------------------------------------------------------------------
    // Aplicar nivel de graves (0..100) — misma lógica que la versión web:
    // shelf de graves + punch, con headroom automático para no distorsionar.
    // ---------------------------------------------------------------------

    private fun applyBassLevel(level: Int) {
        currentLevel = level.coerceIn(0, 100)
        val t = currentLevel / 100f

        if (usingDynamicsProcessing && dynamicsProcessing != null) {
            val dp = dynamicsProcessing!!
            val subDb = t * MAX_SUB_BOOST_DB
            val deepDb = t * MAX_DEEP_BOOST_DB
            // Banda 0 (sub-grave) y banda 1 (golpe profundo) se mueven con el
            // slider. Bandas 2 (anti-cajoneo, -3dB fija) y 3 (resto, 0dB fija)
            // nunca se tocan aquí, igual que en la inicialización.
            dp.setPreEqBandAllChannelsTo(0, DynamicsProcessing.EqBand(true, 45f, subDb))
            dp.setPreEqBandAllChannelsTo(1, DynamicsProcessing.EqBand(true, 75f, deepDb))

            // Compensación de volumen general (equivalente al "masterInput.gain"
            // del script web): a más grave, un poco menos de ganancia general,
            // para dejar margen y que el limitador no tenga que trabajar tanto.
            val inputGainDb = -(t * MAX_INPUT_GAIN_CUT_DB)
            dp.setInputGainAllChannelsTo(inputGainDb)
        } else {
            // Fallback: BassBoost.setStrength admite 0..1000
            bassBoost?.setStrength((t * 1000).toInt().toShort())
            // Compensa un poco el volumen general para evitar saturación,
            // igual que hacíamos con el preGain en la versión de navegador.
            loudnessEnhancer?.setTargetGain((-t * 300).toInt()) // en milibeles, valor negativo = atenuar
        }

        updateNotification()
    }

    private fun releaseEffects() {
        dynamicsProcessing?.release()
        bassBoost?.release()
        equalizer?.release()
        loudnessEnhancer?.release()
    }

    // ---------------------------------------------------------------------
    // Notificación persistente (obligatoria para un foreground service)
    // ---------------------------------------------------------------------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Bass Boost Global",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Bass Boost activo")
            .setContentText("Grave: $currentLevel%")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIF_ID, buildNotification())
    }
}
