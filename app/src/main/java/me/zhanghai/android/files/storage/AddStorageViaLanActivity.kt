/*
 * Copyright (c) 2024 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.storage

import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.util.dpToDimension
import me.zhanghai.android.files.util.dpToDimensionPixelOffset
import me.zhanghai.android.files.util.getColorByAttr
import me.zhanghai.android.files.util.showToast
import me.zhanghai.android.files.webtransfer.WebTransferServer
import qrcode.QRCode

class AddStorageViaLanActivity : AppActivity() {
    private lateinit var qrImageView: ImageView
    private lateinit var qrProgressBar: ProgressBar
    private lateinit var urlTextView: TextView
    private lateinit var statusTextView: TextView
    private lateinit var backButton: TextView
    private lateinit var successOverlay: LinearLayout
    private lateinit var successTitleText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_storage_via_lan)

        qrImageView = findViewById(R.id.qrImageView)
        qrProgressBar = findViewById(R.id.qrProgressBar)
        urlTextView = findViewById(R.id.urlTextView)
        statusTextView = findViewById(R.id.statusTextView)
        backButton = findViewById(R.id.backButton)
        successOverlay = findViewById(R.id.successOverlay)
        successTitleText = findViewById(R.id.successTitleText)

        setupFocusableTvButton(backButton) {
            finish()
        }

        backButton.post {
            backButton.requestFocus()
        }

        startAndDisplayServer()

        LanStorageEventBus.lastAddedStorage.observe(this) { storage ->
            if (storage != null) {
                onStorageAdded(storage)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        LanStorageEventBus.lastAddedStorage.value = null
    }

    private fun onStorageAdded(storage: Storage) {
        val displayName = storage.getName(this)
        val detail = storage.description
        val displayInfo = if (detail.isNotEmpty() && detail != displayName) {
            "$displayName ($detail)"
        } else {
            displayName
        }
        successTitleText.text = getString(R.string.storage_lan_success_format, displayInfo)
        successOverlay.visibility = View.VISIBLE
        showToast(getString(R.string.storage_lan_success_format, displayInfo))

        lifecycleScope.launch {
            delay(1500)
            finish()
        }
    }

    private fun setupFocusableTvButton(button: TextView, action: () -> Unit) {
        button.isFocusable = true
        button.isClickable = true
        button.isFocusableInTouchMode = false
        button.setOnClickListener { action() }

        val primaryColor = getColorByAttr(androidx.appcompat.R.attr.colorPrimary)
        val onPrimaryColor = getColorByAttr(com.google.android.material.R.attr.colorOnPrimary)
        val cornerRadius = dpToDimension(10f)
        val strokeWidth = dpToDimensionPixelOffset(2f)

        val focusedDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            this.cornerRadius = cornerRadius
            setColor(primaryColor)
            setStroke(strokeWidth, onPrimaryColor)
        }

        val normalDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            this.cornerRadius = cornerRadius
            setColor(0x1F8E929E.toInt())
            setStroke(dpToDimensionPixelOffset(1f), 0x2EFFFFFF.toInt())
        }

        button.background = normalDrawable
        button.setTextColor(0xFFD0D4E0.toInt())

        button.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                v.animate().scaleX(1.04f).scaleY(1.04f).setDuration(120).start()
                v.background = focusedDrawable
                button.setTextColor(onPrimaryColor)
            } else {
                v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
                v.background = normalDrawable
                button.setTextColor(0xFFD0D4E0.toInt())
            }
        }
    }

    private fun startAndDisplayServer() {
        qrProgressBar.visibility = View.VISIBLE
        qrImageView.setImageDrawable(null)

        lifecycleScope.launch(Dispatchers.IO) {
            WebTransferServer.start()
            val isRunning = WebTransferServer.isServerRunning()
            val baseUrl = WebTransferServer.getAccessUrl()
            val storageUrl = baseUrl + "storage"

            var qrBitmap: android.graphics.Bitmap? = null
            if (isRunning) {
                try {
                    val qrBytes = QRCode.ofSquares()
                        .build(storageUrl)
                        .renderToBytes()
                    qrBitmap = BitmapFactory.decodeByteArray(qrBytes, 0, qrBytes.size)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            withContext(Dispatchers.Main) {
                qrProgressBar.visibility = View.GONE
                if (isRunning) {
                    urlTextView.text = storageUrl
                    urlTextView.setTextColor(getColorByAttr(androidx.appcompat.R.attr.colorPrimary))
                    statusTextView.setText(R.string.storage_lan_waiting)
                    statusTextView.setTextColor(0xFF30D158.toInt())
                    if (qrBitmap != null) {
                        qrImageView.setImageBitmap(qrBitmap)
                    }
                } else {
                    statusTextView.text = "服务启动失败，请检查网络"
                    statusTextView.setTextColor(0xFFFF5252.toInt())
                    urlTextView.text = "未连接 Wi-Fi"
                }
            }
        }
    }
}
