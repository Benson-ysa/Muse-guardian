package io.zer0.muse.guardian

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.zer0.common.Logger

/**
 * Muse Guardian · 应用拦截/陪伴浮层。
 *
 * 当角色决定在用户想"逃"的时候挡一下(例如打开娱乐应用时),弹一个居中卡片:
 * 角色的一句话 + 两个按钮「再陪我 15 分钟」/「好吧,我走了」。两者都只是关掉浮层——
 * 这是温和的提醒,不强行限制,符合"延迟关怀"的设计。
 *
 * 复用工程既有的 TYPE_APPLICATION_OVERLAY 悬浮窗模式(参照 QuickCaptureOverlayService),
 * 需 SYSTEM_ALERT_WINDOW 权限;未授予时静默退场。由 show_focus_overlay 工具触发。
 */
class InterceptOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        startForegroundCompat(buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) {
            Logger.w(TAG, "无悬浮窗权限(SYSTEM_ALERT_WINDOW),无法展示拦截浮层")
            stopSelf()
            return START_NOT_STICKY
        }
        val message = intent?.getStringExtra(EXTRA_MESSAGE)?.takeIf { it.isNotBlank() }
            ?: DEFAULT_MESSAGE
        showOverlay(message)
        return START_NOT_STICKY
    }

    private fun showOverlay(message: String) {
        removeOverlay()
        val scrim = FrameLayout(this).apply {
            setBackgroundColor(COLOR_SCRIM)
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(16))
            background = rounded(COLOR_CARD, dp(24))
            elevation = dp(16).toFloat()
        }
        card.addView(
            TextView(this).apply {
                text = message
                textSize = 16f
                setTextColor(COLOR_ON_CARD)
            },
        )
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(18) }
            layoutParams = lp
        }
        row.addView(ghostButton(getStayText()) { dismiss() })
        row.addView(
            primaryButton(getLeaveText()) { dismiss() }.apply {
                (layoutParams as? LinearLayout.LayoutParams)?.leftMargin = dp(8)
            },
        )
        card.addView(row)

        val cardParams = FrameLayout.LayoutParams(
            dp(300),
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER,
        )
        scrim.addView(card, cardParams)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            // 不加 FLAG_NOT_TOUCH_MODAL:全屏遮罩消费触摸,真正"挡住"下层应用;FLAG_NOT_FOCUSABLE 避免抢输入焦点。
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        )
        try {
            windowManager.addView(scrim, params)
            overlayView = scrim
        } catch (e: RuntimeException) {
            Logger.w(TAG, "添加拦截浮层失败: ${e.message}")
            stopSelf()
        }
    }

    private fun dismiss() {
        removeOverlay()
        stopSelf()
    }

    private fun removeOverlay() {
        overlayView?.let { view -> runCatching { windowManager.removeView(view) } }
        overlayView = null
    }

    private fun primaryButton(label: String, onClick: () -> Unit): TextView = buttonBase(label).apply {
        setTextColor(COLOR_ON_PRIMARY)
        background = rounded(COLOR_PRIMARY, dp(18))
        setOnClickListener { onClick() }
    }

    private fun ghostButton(label: String, onClick: () -> Unit): TextView = buttonBase(label).apply {
        setTextColor(COLOR_PRIMARY)
        background = rounded(Color.TRANSPARENT, dp(18))
        setOnClickListener { onClick() }
    }

    private fun buttonBase(label: String): TextView = TextView(this).apply {
        text = label
        textSize = 14f
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(10), dp(16), dp(10))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
    }

    private fun getStayText(): String = "再陪我 15 分钟"
    private fun getLeaveText(): String = "好吧，我走了"

    private fun rounded(color: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
    }

    private fun overlayType(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    } else {
        @Suppress("DEPRECATION")
        WindowManager.LayoutParams.TYPE_PHONE
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun buildNotification(): Notification {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Guardian 陪伴浮层", NotificationManager.IMPORTANCE_LOW),
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R_ICON)
            .setContentTitle("muse 想陪你一下")
            .setOngoing(true)
            .build()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        removeOverlay()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "GuardianOverlay"
        private const val NOTIFICATION_ID = 2801
        private const val CHANNEL_ID = "guardian_overlay"
        private const val EXTRA_MESSAGE = "guardian_overlay_message"
        private const val DEFAULT_MESSAGE = "先陪我待一会儿好不好？这个可以等等再看。"
        private val R_ICON = android.R.drawable.ic_dialog_info

        private val COLOR_SCRIM = 0x99000000.toInt()
        private val COLOR_CARD = 0xFFFFFFFF.toInt()
        private val COLOR_ON_CARD = 0xFF1C1B1F.toInt()
        private val COLOR_PRIMARY = 0xFF6750A4.toInt()
        private val COLOR_ON_PRIMARY = 0xFFFFFFFF.toInt()

        /** 展示拦截浮层。message 为角色要说的一句话。 */
        fun show(context: Context, message: String) {
            val intent = Intent(context, InterceptOverlayService::class.java).apply {
                putExtra(EXTRA_MESSAGE, message)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, InterceptOverlayService::class.java))
        }
    }
}
