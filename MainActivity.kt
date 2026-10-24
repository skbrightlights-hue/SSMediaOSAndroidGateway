package com.ssmediaos.smsgateway

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var baseUrl: EditText
    private lateinit var deviceCode: EditText
    private lateinit var token: EditText
    private lateinit var status: TextView
    private lateinit var startButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        SecureConfig.load(this)?.let {
            baseUrl.setText(it.optString("baseUrl"))
            deviceCode.setText(it.optString("deviceCode"))
            token.setText(it.optString("token"))
            status.text = "Configuration saved. SMS service is not necessarily running."
        }
    }

    private fun buildUi() {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(20))
            setBackgroundColor(0xFFF4F7FB.toInt())
        }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val title = TextView(this).apply {
            text = "SS MediaOS SMS Gateway"
            textSize = 24f
            setTextColor(0xFF123B70.toInt())
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(6))
        }
        content.addView(title)
        content.addView(label("Connect this phone to your SS MediaOS server"))
        content.addView(label("Server URL (HTTPS)"))
        baseUrl = edit("https://your-domain.com", false)
        content.addView(baseUrl)
        content.addView(label("Device Code (example: SMS-DEVICE-01)"))
        deviceCode = edit("SMS-DEVICE-01", false)
        content.addView(deviceCode)
        content.addView(label("Device Token (copy from Integrations page)"))
        token = edit("Paste device token", true)
        content.addView(token)
        content.addView(label("Keep this phone powered on, connected to the internet, and with an active SIM. The app sends SMS using your mobile network."))
        val save = Button(this).apply { text = "Save Configuration"; setOnClickListener { saveConfig() } }
        content.addView(save, matchWrap())
        startButton = Button(this).apply { text = "Save & Start SMS Service"; setOnClickListener { saveAndStart() } }
        content.addView(startButton, matchWrap())
        content.addView(Button(this).apply {
            text = "Stop SMS Service"
            setOnClickListener { stopService(Intent(this@MainActivity, GatewayService::class.java)); status.text = "SMS service stopped" }
        }, matchWrap())
        content.addView(Button(this).apply {
            text = "Clear Saved Credentials"
            setOnClickListener { stopService(Intent(this@MainActivity, GatewayService::class.java)); SecureConfig.clear(this@MainActivity); baseUrl.text.clear(); deviceCode.text.clear(); token.text.clear(); status.text = "Saved credentials cleared" }
        }, matchWrap())
        status = TextView(this).apply {
            text = "Status: not started"
            textSize = 15f
            setTextColor(0xFF263238.toInt())
            setPadding(0, dp(16), 0, dp(8))
        }
        content.addView(status)
        content.addView(label("Important: grant SMS and notification permissions when asked. Android battery optimization can stop background work; allow unrestricted battery use for this app if your phone manufacturer requires it."))
        scroll.addView(content)
        outer.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(outer)
    }

    private fun label(value: String) = TextView(this).apply {
        text = value; textSize = 14f; setTextColor(0xFF37474F.toInt()); setPadding(0, dp(10), 0, dp(5))
    }

    private fun edit(hintText: String, secret: Boolean) = EditText(this).apply {
        hint = hintText
        textSize = 15f
        setSingleLine(!secret)
        if (secret) inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = getDrawable(android.R.drawable.edit_text)
    }

    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun saveConfig(): Boolean {
        val url = baseUrl.text.toString().trim().trimEnd('/')
        val code = deviceCode.text.toString().trim()
        val secret = token.text.toString().trim()
        val parsed = try { Uri.parse(url) } catch (_: Exception) { null }
        if (parsed == null || parsed.scheme != "https" || parsed.host.isNullOrBlank() || parsed.userInfo != null) {
            baseUrl.error = "Enter a valid HTTPS server URL"; return false
        }
        if (code.isBlank() || code.length > 80) { deviceCode.error = "Enter the registered device code"; return false }
        if (secret.length < 32 || secret.length > 200) { token.error = "Paste the full device token"; return false }
        SecureConfig.save(this, url, code, secret)
        status.text = "Configuration saved securely on this phone."
        Toast.makeText(this, "Configuration saved", Toast.LENGTH_SHORT).show()
        return true
    }

    private fun saveAndStart() {
        if (!saveConfig()) return
        val missing = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.SEND_SMS)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.POST_NOTIFICATIONS)
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), 700)
            return
        }
        startGateway()
    }

    private fun startGateway() {
        val config = SecureConfig.load(this)
        if (config == null) { status.text = "Configuration could not be read. Please save it again."; return }
        val intent = Intent(this, GatewayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        status.text = "SMS service started. Check the persistent notification for connection status."
        Toast.makeText(this, "SMS service started", Toast.LENGTH_LONG).show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 700) {
            if (permissions.isNotEmpty() && permissions.indices.all { grantResults.getOrNull(it) == PackageManager.PERMISSION_GRANTED }) startGateway()
            else {
                status.text = "Required permission denied. SMS sending cannot start without SMS permission."
                Toast.makeText(this, "Grant SMS permission to continue", Toast.LENGTH_LONG).show()
            }
        }
    }
}
