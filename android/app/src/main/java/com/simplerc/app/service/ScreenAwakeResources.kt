package com.simplerc.app.service

/** The same lifecycle driver is exercised with Android windows and deterministic test devices. */
interface AwakeWindowHandle {
    val attached:Boolean
    val visible:Boolean
    fun attach()
    fun renew()
    fun remove()
}
interface ScreenAwakeDriver {
    val displayHeld:Boolean
    fun acquireDisplay(timeoutMs:Long)
    fun releaseDisplay()
    fun setForegroundKeepScreenOn(keep:Boolean)
    fun createWindow(type:AwakeWindow,host:Any):AwakeWindowHandle
}
data class AwakeResourceResult(val displayHeld:Boolean,val windowReady:Boolean,val windowPending:Boolean,
    val releasePending:Boolean,val errors:Set<String>)

class ScreenAwakeResources(private val driver:ScreenAwakeDriver,
    private val onError:(String,Exception)->Unit={_,_->}) {
    private data class Window(val handle:AwakeWindowHandle,val type:AwakeWindow,val host:Any,
        val created:Long,var renewed:Long,var wasReady:Boolean=false,var closing:Boolean=false)
    private var window:Window?=null
    private var displayRenewed:Long?=null
    private var lastDisplayHeld=false
    private var foregroundMayHold=false
    val needsMaintenance:Boolean get()=window!=null || lastDisplayHeld || foregroundMayHold

    fun apply(plan:ScreenAwakePlan,host:Any?,now:Long):AwakeResourceResult {
        val errors=mutableSetOf<String>()
        fun attempt(action:String,block:()->Unit):Boolean = try {block();true} catch(error:Exception) {
            errors.add(action);onError(action,error);false
        }
        if(attempt("foreground") {driver.setForegroundKeepScreenOn(plan.foregroundWindow)})foregroundMayHold=plan.foregroundWindow
        else foregroundMayHold=true
        if(plan.displayLock) {
            attempt("display-acquire") {
                if(!driver.displayHeld || displayRenewed?.let {now-it>=30_000}!=false) {
                    driver.acquireDisplay(120_000);displayRenewed=now
                }
            }
        } else {
            if(attempt("display-release") {if(driver.displayHeld)driver.releaseDisplay()})displayRenewed=null
        }
        if(!attempt("display-query") {lastDisplayHeld=driver.displayHeld})lastDisplayHeld=true

        fun closeWindow():Boolean {
            val previous=window?:return true
            previous.closing=true
            // Retain ownership on failure so later idle ticks can retry removal.
            if(!attempt("window-remove") {previous.handle.remove()})return false
            window=null
            return true
        }
        val previous=window
        var previousReady=false
        if(previous!=null && !previous.closing) {
            if(attempt("window-health") {previousReady=previous.handle.attached && previous.handle.visible}) {
                if(previousReady)previous.wasReady=true
            }
        }
        val matches=previous!=null && previous.type==plan.window && previous.host===host
        val attachTimedOut=previous!=null && !previousReady && (previous.wasReady || now-previous.created>=3000)
        val healthFailed="window-health" in errors
        if(previous!=null && (previous.closing || !matches || plan.window==AwakeWindow.NONE || attachTimedOut || healthFailed))closeWindow()

        if(window==null && plan.window!=AwakeWindow.NONE && host!=null) {
            attempt("window-create") {
                // Own the handle before attach: even a partially failed add must be cleaned up.
                val created=Window(driver.createWindow(plan.window,host),plan.window,host,now,now)
                window=created
                if(!attempt("window-attach") {created.handle.attach()})closeWindow()
            }
        }
        val current=window
        if(current!=null && !current.closing && now-current.renewed>=30_000) {
            if(attempt("window-renew") {current.handle.renew()})current.renewed=now
            else closeWindow()
        }
        var ready=false
        window?.takeUnless {it.closing}?.let {active->
            attempt("window-health") {ready=active.handle.attached && active.handle.visible}
            if(ready)active.wasReady=true
        }
        val releasePending=(!plan.displayLock && lastDisplayHeld) ||
            (!plan.foregroundWindow && foregroundMayHold) || window?.closing==true ||
            (plan.window==AwakeWindow.NONE && window!=null)
        return AwakeResourceResult(lastDisplayHeld,ready,window!=null && !ready,releasePending,errors)
    }
}
