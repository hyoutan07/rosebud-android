package jp.hyoutan.rosebud

import android.app.Application

class ProbeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val limits=getSharedPreferences("handy_connection",MODE_PRIVATE)
        if(!limits.getBoolean("default_87_applied",false)) {
            val old=if(limits.contains("upper_mm")) limits.getFloat("upper_mm",HandyLimits.DEFAULT_UPPER_MM) else null
            limits.edit().putFloat("upper_mm",HandyLimits.migratedUpper(old)).putBoolean("default_87_applied",true).apply()
        }
    }
    internal val repository by lazy { ProbeRepository(this) }
    @Volatile internal var serviceActive = false
    @Volatile internal var control = ControlSnapshot()
    @Volatile internal var handyServiceActive = false
    @Volatile internal var handyState = HandySnapshot()
    @Volatile internal var handySound = SoundFeatures()
    @Volatile internal var handySoundAt = 0L
}
