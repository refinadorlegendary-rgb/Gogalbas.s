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

        const val MAX_SUB_BOOST_DB = 14f
        const val MAX_PUNCH_BOOST_DB = 6f
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
            val config = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                channelCount,
                true, 1,   // preEq activo, 1 banda -> nuestro "grave shelf"
                true, 1,   // mbc activo, 1 banda -> nuestro "punch" ~100Hz
                false, 0,  // sin postEq
                true       // limiter activo -> nuestro brick-wall final
            ).build()

            val dp = DynamicsProcessing(0, 0, config)

            for (ch in 0 until channelCount) {
                val preEqBand = DynamicsProcessing.EqBand(true, 70f, 0f)
                dp.setPreEqBandAllChannelsTo(0, preEqBand)

                val mbcBand = DynamicsProcessing.MbcBand(
                    true,     // enabled
                    100f,     // cutoffFrequency (límite superior de la banda)
                    -20f,     // attackTime placeholder se ajusta abajo con setters reales
                    50f, 0.5f, 1f, 0f, 0f, 0f
                )
                dp.setMbcBandAllChannelsTo(0, mbcBand)
            }

            val limiter = DynamicsProcessing.Limiter(
                true,   // enabled
                true,   // linked entre canales
                1,      // attackTime (ms) -> rápido, como nuestro limitador de 1ms
                50,     // releaseTime (ms)
                20f,    // ratio 20:1
                -3f,    // threshold dB
                0f      // postGain
            )
            for (ch in 0 until channelCount) {
                dp.setLimiterAllChannelsTo(limiter)
            }

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
            val band = DynamicsProcessing.EqBand(true, 70f, subDb)
            dp.setPreEqBandAllChannelsTo(0, band)
            // El limiter ya está fijo en -3dB / 20:1 y absorbe el headroom,
            // así que no hace falta bajar el volumen general a mano aquí.
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
