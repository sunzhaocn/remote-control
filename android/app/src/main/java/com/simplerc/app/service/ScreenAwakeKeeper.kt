package com.simplerc.app.service

import android.app.Activity
import android.app.Application
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.content.ContextCompat
import com.simplerc.app.MainActivity
import com.simplerc.app.accessibility.RemoteControlAccessibilityService
import com.simplerc.app.model.DeviceRole
import com.simplerc.app.state.AppStateStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Collections
import java.util.WeakHashMap

/** One display owner for both roles, independent of the Activity and video/input statistics. */
class ScreenAwakeKeeper(private val app:Application):Application.ActivityLifecycleCallbacks {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val leases=ScreenAwakeLeases()
    private val power=app.getSystemService(PowerManager::class.java)
    private val keyguard=app.getSystemService(KeyguardManager::class.java)
    private val preferences=app.getSharedPreferences("screen_awake_diagnostics",Context.MODE_PRIVATE)
    @Suppress("DEPRECATION")
    private val displayLock=power.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK,"SimpleRC:session-display")
        .apply {setReferenceCounted(false)}
    private val resumed=mutableSetOf<Activity>()
    private val activities=Collections.newSetFromMap(WeakHashMap<Activity,Boolean>())
    private val handler=Handler(Looper.getMainLooper())
    private var screenOff=false
    private val resources=ScreenAwakeResources(object:ScreenAwakeDriver {
        override val displayHeld:Boolean get()=displayLock.isHeld
        override fun acquireDisplay(timeoutMs:Long)=displayLock.acquire(timeoutMs)
        override fun releaseDisplay()=displayLock.release()
        override fun setForegroundKeepScreenOn(keep:Boolean) {
            var failure:Exception?=null
            activities.toList().forEach {activity->
                if(!activity.isDestroyed)try {
                    val wanted=keep && activity in resumed
                    val window=activity.window
                    if(window.decorView.keepScreenOn!=wanted)window.decorView.keepScreenOn=wanted
                    val held=window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON!=0
                    if(wanted && !held)window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else if(!wanted && held)window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } catch(error:Exception) {failure=error}
            }
            failure?.let {throw it}
        }
        override fun createWindow(type:AwakeWindow,host:Any)=createWindowHandle(type,host as Context)
    }) {action,error->android.util.Log.w("SimpleRcPower","Screen resource $action failed; will retry",error)}
    private var status="亮屏保护：未连接，已释放"
    private var started=false
    private val receiver=object:BroadcastReceiver() {
        override fun onReceive(context:Context?,intent:Intent?) {
            when(intent?.action) {
                Intent.ACTION_SCREEN_OFF->{
                    // SCREEN_OFF does not reveal whether the cause was a power key,
                    // timeout or vendor policy. Record evidence before releasing.
                    val role=if(AppStateStore.state.value.role==DeviceRole.CONTROLLER)"控制端" else "被控端"
                    val event="${SimpleDateFormat("MM-dd HH:mm:ss",Locale.ROOT).format(Date())} $role 收到系统灭屏；灭屏前：$status"
                    preferences.edit().putString("last_screen_off",event).apply()
                    AppStateStore.update {it.copy(lastScreenOff=event)}
                    screenOff=true
                }
                Intent.ACTION_SCREEN_ON,Intent.ACTION_USER_PRESENT->screenOff=false
            }
            refresh()
        }
    }
    fun start() {
        if(started)return
        started=true
        AppStateStore.update {it.copy(lastScreenOff=preferences.getString("last_screen_off","").orEmpty())}
        app.registerActivityLifecycleCallbacks(this)
        ContextCompat.registerReceiver(app,receiver,IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF);addAction(Intent.ACTION_SCREEN_ON);addAction(Intent.ACTION_USER_PRESENT)
        },ContextCompat.RECEIVER_NOT_EXPORTED)
        scope.launch {
            AppStateStore.state.map {leases.wanted(it)}.distinctUntilChanged().collect {refresh()}
        }
        scope.launch {
            while(isActive) {
                delay(15_000)
                if(leases.wanted(AppStateStore.state.value) || resources.needsMaintenance)refresh()
            }
        }
    }
    fun addLease(owner:Any,mode:ForegroundMode,sessionId:String?) {leases.add(owner,mode,sessionId);refresh()}
    fun removeLease(owner:Any) {leases.remove(owner);refresh()}

    fun refresh() {
        if(!started)return
        // A failing vendor/system query must not cancel the long-running observer
        // or watchdog. Keep outstanding resources owned until the next attempt.
        try {refreshResources()} catch(error:Exception) {
            android.util.Log.w("SimpleRcPower","Screen protection check failed; will retry",error)
            status="亮屏保护检查异常，稍后重试"
            AppStateStore.update {it.copy(screenAwakeStatus=status)}
        }
    }
    private fun refreshResources() {
        val wanted=leases.wanted(AppStateStore.state.value)
        val foreground=resumed.isNotEmpty()
        // Disconnect cleanup must run even if unrelated power/keyguard/app-op
        // queries are unavailable. The idle plan needs none of those services.
        val interactive=if(wanted)power.isInteractive else false
        val locked=wanted && keyguard.isKeyguardLocked
        val accessibility=RemoteControlAccessibilityService.instance
        val overlayPermission=if(wanted)Settings.canDrawOverlays(app) else AppStateStore.state.value.overlayPermissionGranted
        val plan=ScreenAwakePolicy.plan(wanted,foreground,interactive,locked,screenOff,accessibility!=null,overlayPermission)
        val host=when(plan.window) {AwakeWindow.ACCESSIBILITY->accessibility;AwakeWindow.APPLICATION->app;else->null}
        val result=resources.apply(plan,host,SystemClock.elapsedRealtime())
        val lockStatus=if(result.displayHeld)"已持有" else if(result.errors.any {it.startsWith("display")})"请求失败" else "未持有"
        val next=when {
            result.releasePending->"亮屏保护：正在释放或重建，失败资源将继续重试"
            !wanted->"亮屏保护：未连接，已释放"
            screenOff || !interactive->"亮屏保护：屏幕已关闭，等待手动点亮"
            locked->"亮屏保护：屏幕已锁定，等待解锁"
            plan.needsPermission->"后台亮屏未就绪：请在后台运行设置中允许悬浮显示"
            "foreground" in result.errors->"亮屏保护：前台窗口更新失败，稍后重试"
            foreground->"亮屏保护：前台窗口；显示锁$lockStatus"
            result.errors.any {it.startsWith("window")}->"后台亮屏窗口检查或更新失败，稍后重试"
            result.windowReady->
                "亮屏保护：后台${if(plan.window==AwakeWindow.ACCESSIBILITY)"无障碍" else "悬浮"}窗口已挂载；显示锁$lockStatus"
            else->"亮屏保护：等待后台窗口挂载"
        }
        if(next!=status || AppStateStore.state.value.overlayPermissionGranted!=overlayPermission) {
            status=next
            AppStateStore.update {it.copy(screenAwakeStatus=next,overlayPermissionGranted=overlayPermission,
                lastBackgroundScreenAwake=if(wanted && !foreground && plan.displayLock)next else it.lastBackgroundScreenAwake)}
        }
    }

    private fun createWindowHandle(type:AwakeWindow,host:Context):AwakeWindowHandle {
        val context=if(type==AwakeWindow.APPLICATION && Build.VERSION.SDK_INT>=30) {
            val display=checkNotNull(app.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY))
            app.createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,null)
        } else host
        val manager=context.getSystemService(WindowManager::class.java)
        val density=context.resources.displayMetrics.density
        val dot=View(context).apply {
            keepScreenOn=true
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            background=GradientDrawable().apply {shape=GradientDrawable.OVAL;setColor(Color.rgb(128,100,200))}
        }
        val params=WindowManager.LayoutParams(
            (6*density).toInt().coerceAtLeast(1),(6*density).toInt().coerceAtLeast(1),
            if(type==AwakeWindow.ACCESSIBILITY)WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity=Gravity.TOP or Gravity.START;x=(8*density).toInt();y=(48*density).toInt()
            alpha=if(Build.VERSION.SDK_INT>=31)minOf(0.5f,
                context.getSystemService(android.hardware.input.InputManager::class.java).maximumObscuringOpacityForTouch/2) else 0.5f
            setTitle("SimpleRC 连接亮屏保护")
        }
        dot.addOnAttachStateChangeListener(object:View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view:View) {handler.post {refresh()}}
            override fun onViewDetachedFromWindow(view:View) {handler.post {refresh()}}
        })
        return object:AwakeWindowHandle {
            override val attached:Boolean get()=dot.isAttachedToWindow
            override val visible:Boolean get()=dot.isShown && dot.windowVisibility==View.VISIBLE
            override fun attach() {manager.addView(dot,params)}
            override fun renew() {
                check(dot.isAttachedToWindow && dot.parent!=null)
                dot.keepScreenOn=true
                params.flags=params.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                manager.updateViewLayout(dot,params)
            }
            override fun remove() {
                // Clear the hold first, even if the system delays detaching the view.
                runCatching {
                    dot.keepScreenOn=false
                    params.flags=params.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
                    if(dot.parent!=null)manager.updateViewLayout(dot,params)
                }.onFailure {android.util.Log.w("SimpleRcPower","Window hold clearing failed; removal will still be attempted",it)}
                try {manager.removeViewImmediate(dot)} catch(error:IllegalArgumentException) {
                    if(dot.isAttachedToWindow)throw error
                }
                // Some devices retain a stale ViewRoot parent after detachment.
                // Actual attachment, not that parent pointer, decides completion.
                check(!dot.isAttachedToWindow) {"Window detachment still pending"}
            }
        }
    }
    override fun onActivityResumed(activity:Activity) {
        if(activity is MainActivity) {
            activities.add(activity)
            resumed.add(activity)
        }
        refresh()
    }
    override fun onActivityPaused(activity:Activity) {resumed.remove(activity);refresh()}
    override fun onActivityDestroyed(activity:Activity) {resumed.remove(activity);activities.remove(activity);refresh()}
    override fun onActivityStopped(activity:Activity) {resumed.remove(activity);refresh()}
    override fun onActivityCreated(activity:Activity,savedInstanceState:Bundle?) {
        if(activity is MainActivity)activities.add(activity)
        refresh()
    }
    override fun onActivityStarted(activity:Activity)=Unit
    override fun onActivitySaveInstanceState(activity:Activity,outState:Bundle)=Unit
}
