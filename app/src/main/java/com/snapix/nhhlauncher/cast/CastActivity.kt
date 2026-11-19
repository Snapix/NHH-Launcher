package com.snapix.nhhlauncher.cast

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.snapix.nhhlauncher.cast.cast.CastDiscovery
import com.snapix.nhhlauncher.cast.cast.CastDevice

class CastActivity : ComponentActivity() {
    private lateinit var discovery: CastDiscovery
    private lateinit var list: LinearLayout
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(Color.rgb(22, 9, 9))
        }

        val title = TextView(this).apply {
            text = "Cast"
            textSize = 24f
            setTextColor(Color.WHITE)
            setGravity(Gravity.CENTER_VERTICAL)
        }
        root.addView(title, LinearLayout.LayoutParams(-1, 64))

        status = TextView(this).apply {
            text = "Searching for Cast devices…"
            textSize = 15f
            setTextColor(Color.LTGRAY)
        }
        root.addView(status, LinearLayout.LayoutParams(-1, 52))

        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))

        val close = Button(this).apply {
            text = "CLOSE"
            setOnClickListener { finish() }
        }
        root.addView(close, LinearLayout.LayoutParams(-1, 54))

        setContentView(root)

        discovery = CastDiscovery(this)
        lifecycleScope.launch {
            discovery.flow.collect { devices ->
                list.removeAllViews()
                status.text = if (devices.isEmpty()) {
                    "No Chromecast or Google Cast device found."
                } else {
                    "Select a display"
                }
                devices.forEach { device ->
                    val button = Button(this@CastActivity).apply {
                        text = device.name
                        isAllCaps = false
                        setOnClickListener { requestProjection(device) }
                    }
                    list.addView(button, LinearLayout.LayoutParams(-1, 58).apply {
                        topMargin = 8
                    })
                }
            }
        }
        discovery.start()
    }

    private fun requestProjection(device: CastDevice) {
        val result = Intent().apply {
            putExtra(CastForegroundService.EXTRA_DEVICE_NAME, device.name)
            putExtra(CastForegroundService.EXTRA_DEVICE_HOST, device.host)
            putExtra(CastForegroundService.EXTRA_DEVICE_PORT, device.port)
            putExtra(CastForegroundService.EXTRA_RESOLUTION, "P720")
            putExtra(CastForegroundService.EXTRA_SEGMENT_DURATION, 1.0)
            putExtra(CastForegroundService.EXTRA_WINDOW_SIZE, 3)
            putExtra(CastForegroundService.EXTRA_LIVE_EDGE, 1.0)
        }
        setResult(RESULT_OK, result)
        finish()
    }

    override fun onDestroy() {
        runCatching { discovery.stop() }
        super.onDestroy()
    }
}
