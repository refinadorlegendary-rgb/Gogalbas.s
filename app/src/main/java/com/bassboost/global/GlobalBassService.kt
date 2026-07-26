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

        const val MAX_SUB_BOOST_DB = 10f // Rango seguro para evitar distorsión masiva
        const val MAX_PUNCH_BOOST_DB = 4f
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
                true, 1,   
                true, 1,   
                false, 0,  
                true       
            ).build()

            val dp = DynamicsProcessing(0, 0, config)

            for (ch in 0 until channelCount) {
                // Filtro inicial de graves profundos en torno a los 45Hz-70Hz
                val preEqBand = DynamicsProcessing.EqBand(true, 55f, 0f)
                dp.setPreEqBandAllChannelsTo(0, preEqBand)

                // Compresión multibanda ajustada para control de picos (evita el "tick" o chasquido)
                val mbcBand = DynamicsProcessing.MbcBand(
                    true,     
                    120f,     // Corte de frecuencia similar al script web
                    1f,       // Attack rápido (ms)
                    100f,     // Release moderado para evitar eco
                    4f,       // Ratio equilibrado
                    -16f,     // Threshold de entrada
                    4f,       // Knee
                    0f, 0f, 0f, 0f
                )
                dp.setMbcBandAllChannelsTo(0, mbcBand)
            }

            // Limitador master estricto tipo brick-wall para blindar la salida
            val limiter = DynamicsProcessing.Limiter(
                true,   
                true,   
                1,      
                0.5f,   // Ataque ultrarrápido
                60f,    // Release
                20f,    // Ratio alto
                -4.0f,  // Threshold conservador para cero distorsión
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
            Log.e("GlobalBassService", "No se pudo crear fallback: ${e.message}")
        }
    }

    private fun applyBassLevel(level: Int) {
        currentLevel = level.coerceIn(0, 100)
        val t = currentLevel / 100f

        if (usingDynamicsProcessing && dynamicsProcessing != null) {
            val dp = dynamicsProcessing!!
            val subDb = t * MAX_SUB_BOOST_DB
            // Aplicamos ganancia de graves limpia sobre la frecuencia de 55Hz
            val band = DynamicsProcessing.EqBand(true, 55f, subDb)
            dp.setPreEqBandAllChannelsTo(0, band)
        } else {
            bassBoost?.setStrength((t * 800).toInt().toShort())
            loudnessEnhancer?.setTargetGain((-t * 200).toInt())
        }

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
            .setContentTitle("Bass Boost Pro activo")
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
