package com.bassboost.global

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private var serviceRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val seekBarBass = findViewById<SeekBar>(R.id.bassSeekBar)
        val seekBarHifi = findViewById<SeekBar>(R.id.hifiSeekBar)
        val seekBarTwister = findViewById<SeekBar>(R.id.twisterSeekBar)

        val textBassLevel = findViewById<TextView>(R.id.levelText)
        val textHifiLevel = findViewById<TextView>(R.id.hifiLevelText)
        val textTwisterLevel = findViewById<TextView>(R.id.twisterLevelText)

        val statusText = findViewById<TextView>(R.id.statusText)
        val toggleButton = findViewById<Button>(R.id.toggleButton)
        val switch6d = findViewById<Switch>(R.id.switch6d)

        requestNotificationPermissionIfNeeded()

        toggleButton.setOnClickListener {
            if (!serviceRunning) {
                startBassService()
                serviceRunning = true
                statusText.text = "Servicio activo (global)"
                toggleButton.text = "DESACTIVAR"
                sendBassLevel(seekBarBass.progress)
                sendHifiLevel(seekBarHifi.progress)
                sendTwisterLevel(seekBarTwister.progress)
                send6dToggle(switch6d.isChecked)
            } else {
                stopBassService()
                serviceRunning = false
                statusText.text = "Servicio detenido"
                toggleButton.text = "ACTIVAR"
            }
        }

        seekBarBass.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                textBassLevel.text = "$progress%"
                if (serviceRunning) sendBassLevel(progress)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        seekBarHifi.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                textHifiLevel.text = "$progress%"
                if (serviceRunning) sendHifiLevel(progress)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        seekBarTwister.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                textTwisterLevel.text = "$progress%"
                if (serviceRunning) sendTwisterLevel(progress)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        switch6d.setOnCheckedChangeListener { _, isChecked ->
            if (serviceRunning) {
                send6dToggle(isChecked)
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001
                )
            }
        }
    }

    private fun startBassService() {
        val intent = Intent(this, GlobalBassService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopBassService() {
        stopService(Intent(this, GlobalBassService::class.java))
    }

    private fun sendBassLevel(level: Int) {
        val intent = Intent(this, GlobalBassService::class.java).apply {
            action = GlobalBassService.ACTION_SET_BASS_LEVEL
            putExtra(GlobalBassService.EXTRA_LEVEL, level)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun sendHifiLevel(level: Int) {
        val intent = Intent(this, GlobalBassService::class.java).apply {
            action = GlobalBassService.ACTION_SET_HIFI_LEVEL
            putExtra(GlobalBassService.EXTRA_LEVEL, level)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun sendTwisterLevel(level: Int) {
        val intent = Intent(this, GlobalBassService::class.java).apply {
            action = GlobalBassService.ACTION_SET_TWISTER_LEVEL
            putExtra(GlobalBassService.EXTRA_LEVEL, level)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun send6dToggle(enabled: Boolean) {
        val intent = Intent(this, GlobalBassService::class.java).apply {
            action = GlobalBassService.ACTION_SET_6D_TOGGLE
            putExtra(GlobalBassService.EXTRA_ENABLED, enabled)
        }
        ContextCompat.startForegroundService(this, intent)
    }
}
