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
        const val ACTION_SET_LEVEL = "com.bassboost.global.SET_LEVEL"
        const val EXTRA_LEVEL = "level" // 0..100
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
                true, 2,   // 2 bandas PreEq (Graves en Banda 0, Voz en Banda 1)
                true, 1,   // mbc activo
                false, 0,  
                true       // limiter activo
            ).build()

            val dp = DynamicsProcessing(0, 0, config)

            for (ch in 0 until channelCount) {
                // Al arrancar en nivel 0, la ganancia comienza en 0dB para no alterar el volumen original
                val subBassBand = DynamicsProcessing.EqBand(true, 45f, 0f)
                dp.setPreEqBandAllChannelsTo(0, subBassBand)

                val voiceClarityBand = DynamicsProcessing.EqBand(true, 2500f, 3.0f)
                dp.setPreEqBandAllChannelsTo(1, voiceClarityBand)

                val mbcBand = DynamicsProcessing.MbcBand(
                    true,     
                    120f,     
                    1f,       
                    80f,      
                    4.0f,     
                    -16.0f,   
                    6.0f,     
                    0f, 0f, 0f, 0f
                )
                dp.setMbcBandAllChannelsTo(0, mbcBand)
            }

            val limiter = DynamicsProcessing.Limiter(
                true,   
                true,   
                1,      
                0.5f,   
                50f,    
                20.0f,  
                -4.0f,  
                0f      
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

    private fun applyBassLevel(level: Int) {
        currentLevel = level.coerceIn(0, 100)
        val t = currentLevel / 100f

        if (usingDynamicsProcessing && dynamicsProcessing != null) {
            val dp = dynamicsProcessing!!
            // El grave profundo escala progresivamente únicamente cuando mueves la barra (de 0dB hasta 14dB)
            val subDb = t * 14.0f 
            val subBassBand = DynamicsProcessing.EqBand(true, 45f, subDb)
            dp.setPreEqBandAllChannelsTo(0, subBassBand)
            
            val voiceClarityBand = DynamicsProcessing.EqBand(true, 2500f, 3.0f)
            dp.setPreEqBandAllChannelsTo(1, voiceClarityBand)
        } else {
            bassBoost?.setStrength((t * 800).toInt().toShort())
        }

        // Si la barra está en 0, el LoudnessEnhancer no aplica ninguna atenuación ni ganancia artificial.
        // Solo actúa de forma milimétrica si subes los graves para prevenir distorsión.
        if (loudnessEnhancer == null) {
            try {
                loudnessEnhancer = LoudnessEnhancer(0).apply { enabled = true }
            } catch (_: Exception) {}
        }
        val attenuationMb = if (currentLevel > 0) (-t * 250).toInt() else 0
        loudnessEnhancer?.setTargetGain(attenuationMb)

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
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Deep Bass Pro activo")
            .setContentText("Bajo profundo: $currentLevel%")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIF_ID, buildNotification())
    }
}
