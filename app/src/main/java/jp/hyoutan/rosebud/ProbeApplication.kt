package jp.hyoutan.rosebud

import android.app.Application

class ProbeApplication : Application() {
    internal val repository by lazy { ProbeRepository(this) }
    @Volatile internal var serviceActive = false
    @Volatile internal var control = ControlSnapshot()
}
