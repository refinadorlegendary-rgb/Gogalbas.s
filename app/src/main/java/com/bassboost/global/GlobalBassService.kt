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
                bassLevel = intent.getIntExtra(EXTRA_LEVEL, 0).coerceIn(0, 29)
                applyAllEffects()
            }
            ACTION_SET_HIFI_LEVEL -> {
                hifiLevel = intent.getIntExtra(EXTRA_LEVEL, 0).coerceIn(0, 20)
                applyAllEffects()
            }
            ACTION_SET_TWISTER_LEVEL -> {
                twisterLevel = intent.getIntExtra(EXTRA_LEVEL, 0).coerceIn(0, 15)
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
                dp.setPreEqBandAllChannelsTo(0, DynamicsProcessing.EqBand(true, 28f, 0f))
                dp.setPreEqBandAllChannelsTo(1, DynamicsProcessing.EqBand(true, 130f, 0f))
                dp.setPreEqBandAllChannelsTo(2, DynamicsProcessing.EqBand(true, 10000f, 0f))
                dp.setPreEqBandAllChannelsTo(3, DynamicsProcessing.EqBand(true, 14000f, 0f))

                val mbcBand = DynamicsProcessing.MbcBand(
                    true, 130f, 0.001f, 0.3f, 16.0f, -18.0f, 16.0f, 0f, 1.0f, 14.0f, 14.0f
                )
                dp.setMbcBandAllChannelsTo(0, mbcBand)
            }

            val limiter = DynamicsProcessing.Limiter(true, true, 1, 1.0f, 40f, 20.0f, -14.0f, 0.0f)
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
        val tBass = bassLevel / 29.0f
        val tHifi = hifiLevel / 20.0f
        val tTwister = twisterLevel / 15.0f

        try {
            bassBoost?.setStrength((tBass * 1000).toInt().toShort())
        } catch (_: Exception) {}

        try {
            equalizer?.let { eq ->
                val numBands = eq.numberOfBands
                if (numBands > 0) {
                    val maxMillibels = eq.bandLevelRange[1]
                    val targetLevel = (tBass * maxMillibels * 1.2f).toInt().coerceAtMost(maxMillibels.toInt()).toShort()
                    
                    eq.setBandLevel(0.toShort(), targetLevel)
                    
                    if (numBands > 1) {
                        eq.setBandLevel(1.toShort(), 0.toShort())
                    }
                    if (numBands > 2) {
                        eq.setBandLevel(2.toShort(), 0.toShort())
                    }
                }
            }
        } catch (_: Exception) {}

        if (usingDynamicsProcessing && dynamicsProcessing != null) {
            val dp = dynamicsProcessing!!
            val subDb = tBass * 42.0f
            dp.setPreEqBandAllChannelsTo(0, DynamicsProcessing.EqBand(true, 28f, subDb))
            dp.setPreEqBandAllChannelsTo(1, DynamicsProcessing.EqBand(true, 130f, 0f))
            dp.setPreEqBandAllChannelsTo(2, DynamicsProcessing.EqBand(true, 10000f, tHifi * 12.0f))
            dp.setPreEqBandAllChannelsTo(3, DynamicsProcessing.EqBand(true, 14000f, tTwister * 12.0f))

            val mbcBand = DynamicsProcessing.MbcBand(
                true, 130f, 0.001f, 0.3f, 16.0f, -18.0f, 16.0f, 0f, 1.0f, 14.0f, 14.0f
            )
            dp.setMbcBandAllChannelsTo(0, mbcBand)
        }

        if (loudnessEnhancer == null) {
            try {
                loudnessEnhancer = LoudnessEnhancer(0).apply { enabled = true }
            } catch (_: Exception) {}
        }

        val dynamicVol = (0.35f - (tBass * 0.0055f) * 29f).coerceAtLeast(0.08f)
        val baseGain = (dynamicVol * 100).toInt()
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
            .setContentText("Bajo: $bassLevel | HiFi: $hifiLevel | Twister: $twisterLevel | 6D: $status6d")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIF_ID, buildNotification())
    }
}
