package com.example.screenchange

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.view.WindowManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class CaptureService : Service() {

    companion object {
        @Volatile var running = false

        private const val CHANNEL = "capture"
        private const val NOTIF_ID = 1

        // Réglages de détection (à ajuster si besoin)
        private const val GRID_W = 24          // colonnes d'échantillonnage
        private const val GRID_H = 48          // lignes d'échantillonnage
        private const val CELL_DELTA = 24      // écart de luminosité pour qu'une cellule "change"
        private const val CHANGE_RATIO = 0.03  // % de cellules changées pour parler de changement d'écran
        private const val SETTLE_MS = 500L     // attendre que l'écran se stabilise avant d'enregistrer
    }

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private lateinit var handler: Handler

    private var width = 0
    private var height = 0
    private var prev: IntArray? = null
    private var frameBmp: Bitmap? = null
    private var hasPending = false
    private var saved = 0

    private val saveTask = Runnable {
        val bmp = frameBmp
        if (hasPending && bmp != null) {
            val cropped = Bitmap.createBitmap(bmp, 0, 0, width, height)
            save(cropped)
            cropped.recycle()
            hasPending = false
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || running) return START_NOT_STICKY

        startAsForeground("Capture en cours…")

        val code = intent.getIntExtra("code", 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra("data", Intent::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra("data")
        if (data == null) { stopSelf(); return START_NOT_STICKY }

        thread = HandlerThread("capture").also { it.start() }
        handler = Handler(thread!!.looper)

        val mpm = getSystemService(MediaProjectionManager::class.java)
        projection = mpm.getMediaProjection(code, data)
        projection!!.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, handler)

        val bounds = screenBounds()
        width = bounds.width()
        height = bounds.height()
        val dpi = resources.displayMetrics.densityDpi

        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        display = projection!!.createVirtualDisplay(
            "screen-change", width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader!!.surface, null, handler
        )
        reader!!.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try { process(image) } finally { image.close() }
        }, handler)

        running = true
        return START_NOT_STICKY
    }

    private fun screenBounds(): Rect {
        val wm = getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= 30) {
            wm.maximumWindowMetrics.bounds
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m)
            Rect(0, 0, m.widthPixels, m.heightPixels)
        }
    }

    private fun process(image: Image) {
        val plane = image.planes[0]
        val buf = plane.buffer
        val ps = plane.pixelStride
        val rs = plane.rowStride

        // Signature réduite de l'écran (luminosité d'une grille de points)
        val sig = IntArray(GRID_W * GRID_H)
        for (gy in 0 until GRID_H) {
            val y = ((gy + 0.5) * height / GRID_H).toInt()
            for (gx in 0 until GRID_W) {
                val x = ((gx + 0.5) * width / GRID_W).toInt()
                val o = y * rs + x * ps
                val r = buf.get(o).toInt() and 0xFF
                val g = buf.get(o + 1).toInt() and 0xFF
                val b = buf.get(o + 2).toInt() and 0xFF
                sig[gy * GRID_W + gx] = (r + g + b) / 3
            }
        }

        val p = prev
        prev = sig
        val changed = if (p == null) true else {
            var n = 0
            for (i in sig.indices) if (abs(sig[i] - p[i]) > CELL_DELTA) n++
            n >= sig.size * CHANGE_RATIO
        }
        if (!changed) return

        // Garde la dernière image vue ; on l'enregistre quand l'écran s'est stabilisé
        val bw = rs / ps
        var bmp = frameBmp
        if (bmp == null || bmp.width != bw) {
            bmp?.recycle()
            bmp = Bitmap.createBitmap(bw, height, Bitmap.Config.ARGB_8888)
            frameBmp = bmp
        }
        buf.rewind()
        bmp.copyPixelsFromBuffer(buf)
        hasPending = true
        handler.removeCallbacks(saveTask)
        handler.postDelayed(saveTask, SETTLE_MS)
    }

    private fun save(bmp: Bitmap) {
        val name = "capture_" +
            SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date()) + ".jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ScreenChange")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
        contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        contentResolver.update(uri, values, null, null)

        saved++
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification("$saved capture(s) enregistrée(s)"))
    }

    private fun buildNotification(text: String): Notification =
        Notification.Builder(this, CHANNEL)
            .setContentTitle("Capture auto")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()

    private fun startAsForeground(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Capture d'écran", NotificationManager.IMPORTANCE_LOW)
        )
        val n = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    override fun onDestroy() {
        running = false
        reader?.setOnImageAvailableListener(null, null)
        display?.release()
        reader?.close()
        projection?.stop()
        frameBmp?.recycle()
        thread?.quitSafely()
        super.onDestroy()
    }
}
