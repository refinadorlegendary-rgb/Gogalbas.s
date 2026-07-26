package com.bassboost.global

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private var serviceRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val seekBar = findViewById<SeekBar>(R.id.bassSeekBar)
        val levelText = findViewById<TextView>(R.id.levelText)
        val statusText = findViewById<TextView>(R.id.statusText)
        val toggleButton = findViewById<Button>(R.id.toggleButton)

        requestNotificationPermissionIfNeeded()

        toggleButton.setOnClickListener {
            if (!serviceRunning) {
                startBassService()
                serviceRunning = true
                statusText.text = "Servicio activo (global)"
                toggleButton.text = "DESACTIVAR"
                sendLevelToService(seekBar.progress)
            } else {
                stopBassService()
                serviceRunning = false
                statusText.text = "Servicio detenido"
                toggleButton.text = "ACTIVAR"
            }
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                levelText.text = "$progress%"
                if (serviceRunning) sendLevelToService(progress)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
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

    private fun sendLevelToService(level: Int) {
        val intent = Intent(this, GlobalBassService::class.java).apply {
            action = GlobalBassService.ACTION_SET_LEVEL
            putExtra(GlobalBassService.EXTRA_LEVEL, level)
        }
        ContextCompat.startForegroundService(this, intent)
    }
}
