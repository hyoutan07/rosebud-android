package jp.hyoutan.rosebud

import android.app.Application

class ProbeApplication : Application() {
    internal val repository by lazy { ProbeRepository(this) }
}
