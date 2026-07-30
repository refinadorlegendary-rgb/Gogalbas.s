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

class GlobalBassService : Service() {

    companion object {
        const val CHANNEL_ID = "global_bass_boost_channel"
        const val NOTIF_ID = 1
        
        const val ACTION_SET_BASS_LEVEL = "com.bassboost.global.SET_BASS_LEVEL"
        const val ACTION_SET_HIFI_LEVEL = "com.bassboost.global.SET_HIFI_LEVEL"
        const val ACTION_SET_TWISTER_LEVEL = "com.bassboost.global.SET_TWISTER_LEVEL"
        const val ACTION_SET_6D_TOGGLE = "com.bassboost.global.SET_6D_TOGGLE"

        const val EXTRA_LEVEL = "level" // 0..100
        const val EXTRA_ENABLED = "enabled" // true..false
    }

    private var dynamicsProcessing: DynamicsProcessing? = null
    private var bassBoost: BassBoost? = null
    private var equalizer: Equalizer? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var usingDynamicsProcessing = false

    private var bassLevel = 0
    private var hifiLevel = 0
    private var twisterLevel = 0
    private var is6dEnabled = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        setupGlobalEffectChain()
        applyAllEffects()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SET_BASS_LEVEL -> {
                bassLevel = intent.getIntExtra(EXTRA_LEVEL, 0).coerceIn(0, 100)
                applyAllEffects()
            }
            ACTION_SET_HIFI_LEVEL -> {
                hifiLevel = intent.getIntExtra(EXTRA_LEVEL, 0).coerceIn(0, 100)
                applyAllEffects()
            }
            ACTION_SET_TWISTER_LEVEL -> {
                twisterLevel = intent.getIntExtra(EXTRA_LEVEL, 0).coerceIn(0, 100)
                applyAllEffects()
            }
            ACTION_SET_6D_TOGGLE -> {
                is6dEnabled = intent.getBooleanExtra(EXTRA_ENABLED, false)
                applyAllEffects()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        releaseEffects()
        super.onDestroy()
    }

    private fun setupGlobalEffectChain() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (trySetupDynamicsProcessing()) {
                usingDynamicsProcessing = true
                return
            }
        }
        trySetupLegacyFallback()
    }

    private fun trySetupDynamicsProcessing(): Boolean {
        return try {
            val channelCount = 2
            // Configuramos 4 bandas PreEq para replicar la estructura paralela exacta del motor HD Bass
            val config = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                channelCount,
                true, 4,   // 4 bandas PreEq: [0] SubBass 40Hz, [1] Mid/Voice 2800Hz, [2] HiFi 10000Hz, [3] Twister 14000Hz
                true, 1,   // mbc activo (Canal paralelo de compresión anti-ticks)
                false, 0,  
                true       // limiter activo
            ).build()

            val dp = DynamicsProcessing(0, 0, config)

            for (ch in 0 until channelCount) {
                // Frecuencias milimétricas idénticas al motor de referencia
                dp.setPreEqBandAllChannelsTo(0, DynamicsProcessing.EqBand(true, 40f, 0f))   // Sub-engine / Deep bass
                dp.setPreEqBandAllChannelsTo(1, DynamicsProcessing.EqBand(true, 2800f, 6.0f)) // Claridad de voz limpia
                dp.setPreEqBandAllChannelsTo(2, DynamicsProcessing.EqBand(true, 10000f, 0f)) // Tweeter / Hi-Fi
                dp.setPreEqBandAllChannelsTo(3, DynamicsProcessing.EqBand(true, 14000f, 0f)) // Twister

                // Compresor blindado anti-ticks (Ataque ultrarrápido y liberación controlada)
                val mbcBand = DynamicsProcessing.MbcBand(
                    true,     // enabled
                    130f,     // cutoffFrequency exacto para evitar filtraciones de voz o agudos
                    0.001f,   // attackTime ultra rápido para interceptar micro-transitorios
                    0.3f,     // releaseTime pausado para cero eco
                    16.0f,    // ratio alto para contener nivel extremo
                    -18.0f,   // threshold
                    16.0f,    // kneeWidth
                    0f,       // noiseGateThreshold
                    1f,       // expanderRatio
                    4.0f,     // preGain
                    6.0f      // postGain
                )
                dp.setMbcBandAllChannelsTo(0, mbcBand)
            }

            // Limitador master reforzado definitivo (-14 dB threshold / Ratio 20)
            val limiter = DynamicsProcessing.Limiter(
                true,   // enabled
                true,   // linked
                1,      // linkGroup
                1.0f,   // attackTime
                40f,    // releaseTime
                20.0f,  // ratio idéntico
                -14.0f, // threshold estricto anti-saturación
                0.0f    // postGain
            )
            for (ch in 0 until channelCount) {
                dp.setLimiterAllChannelsTo(limiter)
            }

            dp.enabled = true
            dynamicsProcessing = dp
            true
        } catch (e: Exception) {
            Log.w("GlobalBassService", "DynamicsProcessing no disponible: ${e.message}")
            dynamicsProcessing?.release()
            dynamicsProcessing = null
            false
        }
    }

    private fun trySetupLegacyFallback() {
        try {
            bassBoost = BassBoost(0, 0).apply { enabled = true; setStrength(0) }
            equalizer = Equalizer(0, 0).apply { enabled = true }
            loudnessEnhancer = LoudnessEnhancer(0).apply { enabled = true; setTargetGain(0) }
        } catch (e: Exception) {
            Log.e("GlobalBassService", "Error en fallback: ${e.message}")
        }
    }

    private fun applyAllEffects() {
        // Mapeo lineal exacto de la escala del ejemplo (0 a 100 escalado a la proporción del motor)
        val tBass = (bassLevel / 100f) * 24.0f   // Rango profundo optimizado
        val tHifi = (hifiLevel / 100f) * 12.0f
        val tTwister = (twisterLevel / 100f) * 12.0f

        if (usingDynamicsProcessing && dynamicsProcessing != null) {
            val dp = dynamicsProcessing!!

            // Inyección milimétrica en la banda de Subgrave / Deep Engine (40Hz)
            dp.setPreEqBandAllChannelsTo(0, DynamicsProcessing.EqBand(true, 40f, tBass))
            
            // Banda de voz estable y nítida intacta
            dp.setPreEqBandAllChannelsTo(1, DynamicsProcessing.EqBand(true, 2800f, 6.0f))

            // Hi-Fi / Tweeter
            dp.setPreEqBandAllChannelsTo(2, DynamicsProcessing.EqBand(true, 10000f, tHifi))

            // Twister / Agudos envolventes
            dp.setPreEqBandAllChannelsTo(3, DynamicsProcessing.EqBand(true, 14000f, tTwister))

        } else {
            bassBoost?.setStrength(((bassLevel / 100f) * 1000).toInt().toShort())
        }

        if (loudnessEnhancer == null) {
            try {
                loudnessEnhancer = LoudnessEnhancer(0).apply { enabled = true }
            } catch (_: Exception) {}
        }

        // Control dinámico de ganancia maestro idéntico al motor web para evitar saturación al tope
        val baseGain = if (bassLevel > 0) (35 - (bassLevel * 0.55f)).toInt() else 35
        val spatialGainBoost = if (is6dEnabled) 150 else 0 
        loudnessEnhancer?.setTargetGain(baseGain + spatialGainBoost)

        updateNotification()
    }

    private fun releaseEffects() {
        dynamicsProcessing?.release()
        bassBoost?.release()
        equalizer?.release()
        loudnessEnhancer?.release()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Deep Bass Pro Engine",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val status6d = if (is6dEnabled) "ON" else "OFF"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CarPlayer Pro — Deep Bass HD")
            .setContentText("Bajo: $bassLevel% | HiFi: $hifiLevel% | Twister: $twisterLevel% | 6D: $status6d")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIF_ID, buildNotification())
    }
}
