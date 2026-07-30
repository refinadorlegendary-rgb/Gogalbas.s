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
            val config = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                channelCount,
                true, 4,   // 4 bandas PreEq
                true, 1,   // mbc activo
                false, 0,  
                true       // limiter activo
            ).build()

            val dp = DynamicsProcessing(0, 0, config)

            for (ch in 0 until channelCount) {
                dp.setPreEqBandAllChannelsTo(0, DynamicsProcessing.EqBand(true, 40f, 0f))   
                dp.setPreEqBandAllChannelsTo(1, DynamicsProcessing.EqBand(true, 2800f, 6.0f)) 
                dp.setPreEqBandAllChannelsTo(2, DynamicsProcessing.EqBand(true, 10000f, 0f)) 
                dp.setPreEqBandAllChannelsTo(3, DynamicsProcessing.EqBand(true, 14000f, 0f)) 

                // Compresor optimizado con mayor preGain e inyección de cuerpo
                val mbcBand = DynamicsProcessing.MbcBand(
                    true,     
                    130f,     
                    0.001f,   
                    0.3f,     
                    16.0f,    
                    -18.0f,   
                    16.0f,    
                    0f,       
                    1f,       
                    8.0f,     // Aumentado para inyectar más potencia limpia al subgrave
                    9.0f      // Mayor ganancia posterior para mayor presión sonora
                )
                dp.setMbcBandAllChannelsTo(0, mbcBand)
            }

            val limiter = DynamicsProcessing.Limiter(
                true,   
                true,   
                1,      
                1.0f,   
                40f,    
                20.0f,  
                -14.0f, 
                0.0f    
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
        // Multiplicador incrementado (hasta 32dB al 100%) para un bajo profundo contundente
        val tBass = (bassLevel / 100f) * 32.0f   
        val tHifi = (hifiLevel / 100f) * 12.0f
        val tTwister = (twisterLevel / 100f) * 12.0f

        if (usingDynamicsProcessing && dynamicsProcessing != null) {
            val dp = dynamicsProcessing!!

            dp.setPreEqBandAllChannelsTo(0, DynamicsProcessing.EqBand(true, 40f, tBass))
            dp.setPreEqBandAllChannelsTo(1, DynamicsProcessing.EqBand(true, 2800f, 6.0f))
            dp.setPreEqBandAllChannelsTo(2, DynamicsProcessing.EqBand(true, 10000f, tHifi))
            dp.setPreEqBandAllChannelsTo(3, DynamicsProcessing.EqBand(true, 14000f, tTwister))

        } else {
            bassBoost?.setStrength(((bassLevel / 100f) * 1000).toInt().toShort())
        }

        if (loudnessEnhancer == null) {
            try {
                loudnessEnhancer = LoudnessEnhancer(0).apply { enabled = true }
            } catch (_: Exception) {}
        }

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
