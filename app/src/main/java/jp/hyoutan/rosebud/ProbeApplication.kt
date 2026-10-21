package jp.hyoutan.rosebud

import android.app.Application

class ProbeApplication : Application() {
    internal val repository by lazy { ProbeRepository(this) }
    @Volatile internal var serviceActive = false
    @Volatile internal var control = ControlSnapshot()
    @Volatile internal var handyServiceActive = false
    @Volatile internal var handyState = HandySnapshot()
    @Volatile internal var handySound = SoundFeatures()
    @Volatile internal var handySoundAt = 0L
}
