package jp.hyoutan.rosebud

import android.app.Activity
import android.os.Bundle
import android.view.WindowInsets
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val content = findViewById<android.view.View>(R.id.content)
        val padding = resources.getDimensionPixelSize(R.dimen.content_padding)
        content.setOnApplyWindowInsetsListener { view, windowInsets ->
            val bars = windowInsets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
            )
            view.setPadding(
                padding + bars.left,
                padding + bars.top,
                padding + bars.right,
                padding + bars.bottom,
            )
            windowInsets
        }
        content.requestApplyInsets()

        findViewById<TextView>(R.id.app_version).text = getString(
            R.string.app_version,
            BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE,
        )
    }
}
