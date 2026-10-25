/*
 * Copyright (c) 2024 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.webtransfer

import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import qrcode.QRCode
import java8.nio.file.Paths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.filelist.FileListActivity
import me.zhanghai.android.files.util.dpToDimension
import me.zhanghai.android.files.util.dpToDimensionPixelOffset
import me.zhanghai.android.files.util.getColorByAttr
import me.zhanghai.android.files.util.startActivitySafe

class WebTransferActivity : AppActivity() {
    private lateinit var qrImageView: ImageView
    private lateinit var qrProgressBar: ProgressBar
    private lateinit var urlTextView: TextView
    private lateinit var statusTextView: TextView
    private lateinit var directoryTextView: TextView
    private lateinit var browseButton: TextView
    private lateinit var restartButton: TextView
    private lateinit var backButton: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_web_transfer)

        qrImageView = findViewById(R.id.qrImageView)
        qrProgressBar = findViewById(R.id.qrProgressBar)
        urlTextView = findViewById(R.id.urlTextView)
        statusTextView = findViewById(R.id.statusTextView)
        directoryTextView = findViewById(R.id.directoryTextView)
        browseButton = findViewById(R.id.browseButton)
        restartButton = findViewById(R.id.restartButton)
        backButton = findViewById(R.id.backButton)

        setupFocusableTvButton(browseButton) {
            val downloadPath = Paths.get(WebTransferServer.downloadDirectory.absolutePath)
            startActivitySafe(FileListActivity.createViewIntent(downloadPath))
        }

        setupFocusableTvButton(restartButton) {
            restartServer()
        }

        setupFocusableTvButton(backButton) {
            finish()
        }

        browseButton.post {
            browseButton.requestFocus()
        }
        startAndDisplayServer()
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
            val url = WebTransferServer.getAccessUrl()
            val downloadDir = WebTransferServer.downloadDirectory.absolutePath

            var qrBitmap: android.graphics.Bitmap? = null
            if (isRunning) {
                try {
                    val qrBytes = QRCode.ofSquares()
                        .build(url)
                        .renderToBytes()
                    qrBitmap = BitmapFactory.decodeByteArray(qrBytes, 0, qrBytes.size)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            withContext(Dispatchers.Main) {
                qrProgressBar.visibility = View.GONE
                if (isRunning) {
                    statusTextView.setText(R.string.web_transfer_status_running)
                    statusTextView.setTextColor(0xFF00E676.toInt())
                    urlTextView.text = url
                    urlTextView.setTextColor(getColorByAttr(androidx.appcompat.R.attr.colorPrimary))
                    directoryTextView.text = getString(R.string.web_transfer_dest_dir) + " (" + downloadDir + ")"
                    if (qrBitmap != null) {
                        qrImageView.setImageBitmap(qrBitmap)
                    }
                } else {
                    statusTextView.setText(R.string.web_transfer_status_stopped)
                    statusTextView.setTextColor(0xFFFF5252.toInt())
                    urlTextView.text = "服务启动失败，请检查网络后点击重启"
                }
            }
        }
    }

    private fun restartServer() {
        lifecycleScope.launch(Dispatchers.IO) {
            WebTransferServer.stop()
            withContext(Dispatchers.Main) {
                startAndDisplayServer()
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
