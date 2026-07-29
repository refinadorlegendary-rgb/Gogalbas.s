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
    private var currentLevel = 0 // Arranca estrictamente en 0

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        setupGlobalEffectChain()
        applyBassLevel(0)
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
                true, 2,   // 2 bandas PreEq: Banda 0 (Sub-graves 45Hz), Banda 1 (Claridad de voz 2500Hz)
                true, 1,   // mbc activo
                false, 0,  
                true       // limiter activo
            ).build()

            val dp = DynamicsProcessing(0, 0, config)

            for (ch in 0 until channelCount) {
                // Banda 0: Inicia neutral (0dB) en 45Hz
                val subBassBand = DynamicsProcessing.EqBand(true, 45f, 0f)
                dp.setPreEqBandAllChannelsTo(0, subBassBand)

                // Banda 1: Ganancia fija para mantener la voz clara y limpia
                val voiceClarityBand = DynamicsProcessing.EqBand(true, 2500f, 3.5f)
                dp.setPreEqBandAllChannelsTo(1, voiceClarityBand)

                // CORREGIDO: Frecuencia de corte baja (85Hz) para no tragar la voz, con postGain para recuperar volumen
                val mbcBand = DynamicsProcessing.MbcBand(
                    true,     // enabled
                    90f,      // cutoffFrequency (Antes 120f, ahora respeta la voz)
                    2f,       // attackTime (ms)
                    100f,     // releaseTime (ms)
                    3.0f,     // ratio
                    -12.0f,   // threshold
                    4.0f,     // kneeWidth
                    0f,       // noiseGateThreshold
                    1f,       // expanderRatio
                    8.0f,     // preGain
                    9.0f      // postGain (¡Devuelve el volumen al bajo profundo!)
                )
                dp.setMbcBandAllChannelsTo(0, mbcBand)
            }

            // CORREGIDO: Limiter de seguridad optimizado para evitar distorsión y chasquidos
            val limiter = DynamicsProcessing.Limiter(
                true,   // enabled
                true,   // linked
                1,      // linkGroup
                1.0f,   // attackTime (ms)
                60f,    // releaseTime (ms)
                10.0f,  // ratio (más musical que 20:1)
                -2.0f,  // threshold de seguridad (más cercano a 0 para no apagar el sonido)
                0.5f    // postGain
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
            val subDb = t * 12.0f 
            val subBassBand = DynamicsProcessing.EqBand(true, 45f, subDb)
            dp.setPreEqBandAllChannelsTo(0, subBassBand)
            
            val voiceClarityBand = DynamicsProcessing.EqBand(true, 2500f, 3.5f)
            dp.setPreEqBandAllChannelsTo(1, voiceClarityBand)
        } else {
            bassBoost?.setStrength((t * 800).toInt().toShort())
        }

        if (loudnessEnhancer == null) {
            try {
                loudnessEnhancer = LoudnessEnhancer(0).apply { enabled = true }
            } catch (_: Exception) {}
        }
        val attenuationMb = if (currentLevel > 0) (-t * 200).toInt() else 0
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
