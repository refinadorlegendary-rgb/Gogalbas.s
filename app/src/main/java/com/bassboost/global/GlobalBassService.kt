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
        
        // Acciones para cada control deslizante y el interruptor 6D
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

    // Niveles independientes (arrancan en 0)
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
            // Configuramos 4 bandas PreEq para manejar Bajos, Voz, Hi-Fi y Twister de forma independiente
            val config = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                channelCount,
                true, 4,   // 4 bandas PreEq: [0] SubBass, [1] Voz, [2] HiFi, [3] Twister
                true, 1,   // mbc activo
                false, 0,  
                true       // limiter activo
            ).build()

            val dp = DynamicsProcessing(0, 0, config)

            for (ch in 0 until channelCount) {
                // Inicialización neutral de las 4 bandas
                dp.setPreEqBandAllChannelsTo(0, DynamicsProcessing.EqBand(true, 40f, 0f))
                dp.setPreEqBandAllChannelsTo(1, DynamicsProcessing.EqBand(true, 2500f, 3.5f))
                dp.setPreEqBandAllChannelsTo(2, DynamicsProcessing.EqBand(true, 8000f, 0f))
                dp.setPreEqBandAllChannelsTo(3, DynamicsProcessing.EqBand(true, 14000f, 0f))

                // Compresor multibanda optimizado para bajo profundo y vibratorio sin distorsión en voz
                val mbcBand = DynamicsProcessing.MbcBand(
                    true,     // enabled
                    90f,      // cutoffFrequency (protege la voz humana)
                    1.5f,     // attackTime (ms)
                    120f,     // releaseTime (ms) - mayor sustain para efecto vibratorio
                    3.0f,     // ratio
                    -13.0f,   // threshold
                    4.0f,     // kneeWidth
                    0f,       // noiseGateThreshold
                    1f,       // expanderRatio
                    3.5f,     // preGain para inyectar cuerpo profundo
                    5.0f      // postGain para redondear el subgrave limpiamente
                )
                dp.setMbcBandAllChannelsTo(0, mbcBand)
            }

            // Limitador de seguridad robusto contra picos e intermodulaciones
            val limiter = DynamicsProcessing.Limiter(
                true,   // enabled
                true,   // linked
                1,      // linkGroup
                1.0f,   // attackTime (ms)
                50f,    // releaseTime (ms)
                12.0f,  // ratio
                -1.5f,  // threshold de seguridad óptimo
                0.2f    // postGain
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
        val tBass = bassLevel / 100f
        val tHifi = hifiLevel / 100f
        val tTwister = twisterLevel / 100f

        if (usingDynamicsProcessing && dynamicsProcessing != null) {
            val dp = dynamicsProcessing!!

            // 1. Barra de Bajo Profundo (Banda 0: Subgraves masivos y vibratorios)
            val subDb = tBass * 18.0f 
            dp.setPreEqBandAllChannelsTo(0, DynamicsProcessing.EqBand(true, 40f, subDb))
            
            // Banda 1: Claridad de voz protegida y limpia constante
            dp.setPreEqBandAllChannelsTo(1, DynamicsProcessing.EqBand(true, 2500f, 3.5f))

            // 2. Barra Hi-Fi (Banda 2: Brillo cristalino y alta fidelidad en agudos)
            val hifiDb = tHifi * 10.0f
            dp.setPreEqBandAllChannelsTo(2, DynamicsProcessing.EqBand(true, 8000f, hifiDb))

            // 3. Barra Twister (Banda 3: Presencia envolvente y agudos extremos)
            val twisterDb = tTwister * 12.0f
            dp.setPreEqBandAllChannelsTo(3, DynamicsProcessing.EqBand(true, 14000f, twisterDb))

        } else {
            bassBoost?.setStrength((tBass * 1000).toInt().toShort())
        }

        if (loudnessEnhancer == null) {
            try {
                loudnessEnhancer = LoudnessEnhancer(0).apply { enabled = true }
            } catch (_: Exception) {}
        }

        // Gestión de ganancia y simulación del modo 6D (Inmersión espacial 360°)
        val baseAttenuation = if (bassLevel > 0 || hifiLevel > 0 || twisterLevel > 0) (- (tBass * 150)).toInt() else 0
        val spatialGainBoost = if (is6dEnabled) 150 else 0 
        loudnessEnhancer?.setTargetGain(baseAttenuation + spatialGainBoost)

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
                "Bass Boost Global",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val status6d = if (is6dEnabled) "ON" else "OFF"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Deep Bass Pro + 6D HIFI Activo")
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
