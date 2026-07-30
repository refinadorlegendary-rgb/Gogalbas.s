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

        const val EXTRA_LEVEL = "level"
        const val EXTRA_ENABLED = "enabled"
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
        try {
            bassBoost = BassBoost(0, 0).apply {
                enabled = true
                setStrength(0)
            }
        } catch (e: Exception) {
            Log.w("GlobalBassService", "BassBoost global no soportado: ${e.message}")
        }

        try {
            equalizer = Equalizer(0, 0).apply {
                enabled = true
            }
        } catch (e: Exception) {
            Log.w("GlobalBassService", "Equalizer global no soportado: ${e.message}")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (trySetupDynamicsProcessing()) {
                usingDynamicsProcessing = true
                return
            }
        }
    }

    private fun trySetupDynamicsProcessing(): Boolean {
        return try {
            val channelCount = 2
            val config = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                channelCount,
                true, 4,
                true, 1,
                false, 0,
                true
            ).build()

            val dp = DynamicsProcessing(0, 0, config)
            for (ch in 0 until channelCount) {
                // Banda 0: Subgraves puros a 28Hz (vibración de subwoofer)
                dp.setPreEqBandAllChannelsTo(0, DynamicsProcessing.EqBand(true, 28f, 0f))
                // Banda 1: Graves limpios situados abajo para no interferir con las frecuencias de la voz (fijada en 140Hz)
                dp.setPreEqBandAllChannelsTo(1, DynamicsProcessing.EqBand(true, 140f, 0f))
                dp.setPreEqBandAllChannelsTo(2, DynamicsProcessing.EqBand(true, 10000f, 0f))
                dp.setPreEqBandAllChannelsTo(3, DynamicsProcessing.EqBand(true, 14000f, 0f))

                // Compresor multibanda ajustado para aislar el grave profundo sin saturar medios
                val mbcBand = DynamicsProcessing.MbcBand(
                    true, 100f, 0.002f, 0.4f, 15.0f, -22.0f, 15.0f, 0f, 1.1f, 12.0f, 12.0f
                )
                dp.setMbcBandAllChannelsTo(0, mbcBand)
            }

            val limiter = DynamicsProcessing.Limiter(true, true, 1, 1.0f, 40f, 20.0f, -10.0f, 0.0f)
            for (ch in 0 until channelCount) {
                dp.setLimiterAllChannelsTo(limiter)
            }

            dp.enabled = true
            dynamicsProcessing = dp
            true
        } catch (e: Exception) {
            dynamicsProcessing?.release()
            dynamicsProcessing = null
            false
        }
    }

    private fun applyAllEffects() {
        val tBass = bassLevel / 100f
        val tHifi = hifiLevel / 100f
        val tTwister = twisterLevel / 100f

        try {
            bassBoost?.setStrength((tBass * 850).toInt().toShort()) // Ajustado para evitar saturación metálica en la voz
        } catch (_: Exception) {}

        try {
            equalizer?.let { eq ->
                val numBands = eq.numberOfBands
                if (numBands > 0) {
                    val maxMillibels = eq.bandLevelRange[1] 
                    
                    // Banda 0 al máximo para el golpe profundo
                    val targetLevel = (tBass * maxMillibels).toInt().toShort()
                    eq.setBandLevel(0.toShort(), targetLevel)
                    
                    // Atenuación controlada en bandas superiores para que la voz no se "hunda"
                    if (numBands > 1) {
                        eq.setBandLevel(1.toShort(), (targetLevel * 0.4f).toInt().toShort())
                    }
                    if (numBands > 2) {
                        eq.setBandLevel(2.toShort(), 0.toShort()) // Cero interferencia en la zona vocal
                    }
                }
            }
        } catch (_: Exception) {}

        if (usingDynamicsProcessing && dynamicsProcessing != null) {
            val dp = dynamicsProcessing!!
            // Subgraves profundos a 28Hz con ganancia optimizada, manteniendo la banda 1 neutra para proteger la voz
            val subDb = tBass * 40.0f 
            dp.setPreEqBandAllChannelsTo(0, DynamicsProcessing.EqBand(true, 28f, subDb))
            dp.setPreEqBandAllChannelsTo(1, DynamicsProcessing.EqBand(true, 140f, 2.0f)) // Leve realce que respeta las vocales
            dp.setPreEqBandAllChannelsTo(2, DynamicsProcessing.EqBand(true, 10000f, tHifi * 12.0f))
            dp.setPreEqBandAllChannelsTo(3, DynamicsProcessing.EqBand(true, 14000f, tTwister * 12.0f))

            val mbcBand = DynamicsProcessing.MbcBand(
                true, 100f, 0.002f, 0.4f, 15.0f, -22.0f, 15.0f, 0f, 1.1f, 12.0f, 12.0f
            )
            dp.setMbcBandAllChannelsTo(0, mbcBand)
        }

        if (loudnessEnhancer == null) {
            try {
                loudnessEnhancer = LoudnessEnhancer(0).apply { enabled = true }
            } catch (_: Exception) {}
        }

        // Ganancia inteligente: compensa la pegada sin opacar el brillo de la voz
        val baseGain = if (bassLevel > 0) (35 - (bassLevel * 0.2f)).toInt() else 35
        val spatialGainBoost = if (is6dEnabled) 150 else 0 
        try {
            loudnessEnhancer?.setTargetGain(baseGain + spatialGainBoost)
        } catch (_: Exception) {}

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
