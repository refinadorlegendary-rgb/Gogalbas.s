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
                true, 1,   // preEq activo (estante de graves)
                true, 1,   // mbc activo (compresor multibanda)
                false, 0,  
                true       // limiter activo (brick-wall)
            ).build()

            val dp = DynamicsProcessing(0, 0, config)

            for (ch in 0 until channelCount) {
                // Estante de graves profundos centrado en 45Hz
                val preEqBand = DynamicsProcessing.EqBand(true, 45f, 0f)
                dp.setPreEqBandAllChannelsTo(0, preEqBand)

                // Constructor corregido de MbcBand con los 11 parámetros exactos de Android
                val mbcBand = DynamicsProcessing.MbcBand(
                    true,     // enabled
                    120f,     // cutoffFrequency
                    1f,       // attackTime (ms)
                    80f,      // releaseTime (ms)
                    4.0f,     // ratio
                    -18.0f,   // threshold
                    6.0f,     // kneeWidth
                    0f,       // noiseGateThreshold
                    0f,       // expanderRatio
                    0f,       // preGain
                    0f        // postGain
                )
                dp.setMbcBandAllChannelsTo(0, mbcBand)
            }

            // Constructor corregido de Limiter con los 8 parámetros exactos de Android
            val limiter = DynamicsProcessing.Limiter(
                true,   // enabled
                true,   // linked
                1,      // linkGroup
                0.5f,   // attackTime (ms)
                50f,    // releaseTime (ms)
                20.0f,  // ratio
                -6.0f,  // threshold (-6dB de seguridad)
                0f      // postGain
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
            bassBoost = BassBoost(0, 0).apply { enabled = true }
            equalizer = Equalizer(0, 0).apply { enabled = true }
            loudnessEnhancer = LoudnessEnhancer(0).apply { enabled = true }
        } catch (e: Exception) {
            Log.e("GlobalBassService", "Error en fallback: ${e.message}")
        }
    }

    private fun applyBassLevel(level: Int) {
        currentLevel = level.coerceIn(0, 100)
        val t = currentLevel / 100f

        if (usingDynamicsProcessing && dynamicsProcessing != null) {
            val dp = dynamicsProcessing!!
            val subDb = t * 12.0f 
            val band = DynamicsProcessing.EqBand(true, 45f, subDb)
            dp.setPreEqBandAllChannelsTo(0, band)
        } else {
            bassBoost?.setStrength((t * 700).toInt().toShort())
        }

        if (loudnessEnhancer == null) {
            try {
                loudnessEnhancer = LoudnessEnhancer(0).apply { enabled = true }
            } catch (_: Exception) {}
        }
        val attenuationMb = (-t * 500).toInt()
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
