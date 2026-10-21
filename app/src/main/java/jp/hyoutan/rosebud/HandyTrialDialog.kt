package jp.hyoutan.rosebud

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Input diagram, explicit bounded hardware execution, and user-initiated text export. */
internal class HandyTrialDialog(private val activity: Activity, private val stopAll: () -> Unit, private val onClosed: () -> Unit,
    private val startReal: (HandyTrialPlan)->Unit,
    private val stopReal: ()->Unit,
    private val realState: ()->HandySnapshot) {
    private val prefs = activity.getSharedPreferences("handy_trial_inputs",Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val fields = linkedMapOf<String,EditText>()
    private val original = mutableMapOf<String,String>()
    private var mode = prefs.getString("mode","range").let { if(it=="sequence") "sequence" else "range" }
    private val originalMode = mode
    private var active = false
    private var epoch = 0L
    private var began = 0L
    private var plan: HandyTrialPlan? = null
    private var previewed: HandyTrialPlan? = null
    private var previewStatus = "未実施"
    private lateinit var status: TextView
    private lateinit var previewButton: Button
    private lateinit var realButton: Button
    private var sentPlan: HandyTrialPlan? = null
    private var realResult: String? = null
    private var sawRealRun = false
    private var waitingReal = false
    private var requestedAt = 0L
    private val realTicker = object : Runnable {
        override fun run() {
            if(!::dialog.isInitialized || !dialog.isShowing) return
            val live=realState()
            if(sentPlan!=null) {
                if(live.trialActive) { sawRealRun=true;waitingReal=false;realResult="実施中（完了・物理停止は未確認）" }
                if(sawRealRun && !live.trialActive && !live.stopping) realResult=live.trialStatus
                if(waitingReal && !live.trialActive && SystemClock.elapsedRealtime()-requestedAt>3500) {
                    waitingReal=false;realResult="開始を確認できませんでした：${live.error?.takeIf { it.isNotEmpty() } ?: live.status}"
                }
                if(live.trialActive || live.stopping || waitingReal || sawRealRun) status.text=live.status+
                    (if(live.error.isNullOrEmpty()) "" else "\n${live.error}")+
                    (live.positionMm?.let { "\n受信位置：%.1fmm".format(java.util.Locale.ROOT,it) } ?: "")
            }
            val busy=live.running || live.trialActive || live.stopping || live.connecting || waitingReal
            realButton.isEnabled=live.trialActive || (!busy && live.connected)
            realButton.text=if(live.trialActive) "Handyテストを停止" else "実機で試す"
            setEditable(!busy)
            main.postDelayed(this,250)
        }
    }
    private lateinit var checkButton: Button
    private lateinit var modeRange: Button
    private lateinit var modeSequence: Button
    private lateinit var range: LinearLayout
    private lateinit var sequence: LinearLayout
    private lateinit var chart: HandyTrialChart
    private lateinit var dialog: AlertDialog
    private val ticker = object : Runnable {
        override fun run() {
            val p=plan ?: return
            if(!active) return
            val generation=epoch
            val f=p.frame(SystemClock.elapsedRealtime()-began)
            chart.elapsedMs=f.elapsedMs;chart.invalidate()
            status.text = activity.getString(R.string.handy_trial_progress,f.segment+1,p.points.size,f.elapsedMs/1000.0,p.totalMs/1000.0)
            if(f.complete) {
                active=false;previewStatus="完了";setEditable(true)
                previewButton.setText(R.string.handy_trial_preview)
                status.setText(R.string.handy_trial_preview_completed)
            } else if(active && epoch==generation) main.postDelayed(this,100)
        }
    }
    private fun dp(n: Int) = (n*activity.resources.displayMetrics.density).toInt()
    private fun column() = LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL }
    private fun text(value: String,parent: LinearLayout,small: Boolean=false) = TextView(activity).also {
        it.text=value;it.textSize=if(small) 12f else 14f;it.setPadding(0,dp(8),0,dp(4));parent.addView(it)
    }
    private fun button(title: Int,parent: LinearLayout,action:()->Unit) = Button(activity).also {
        it.setText(title);it.isAllCaps=false;it.textSize=14f;it.minHeight=dp(48)
        parent.addView(it,LinearLayout.LayoutParams(-1,-2));it.setOnClickListener { action() }
    }
    private fun input(parent: LinearLayout,key: String,label: String,hint: String,default: String="",multiline: Boolean=false): EditText {
        text(label,parent)
        return EditText(activity).also {
            it.hint=hint;it.textSize=14f;it.contentDescription=label
            it.inputType=if(multiline) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE else InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            it.filters=arrayOf(InputFilter.LengthFilter(if(multiline) 2000 else 12))
            it.importantForAutofill=View.IMPORTANT_FOR_AUTOFILL_NO
            it.minHeight=dp(if(multiline) 110 else 48)
            it.setText(prefs.getString(key,default));original[key]=it.text.toString();fields[key]=it
            parent.addView(it,LinearLayout.LayoutParams(-1,-2))
        }
    }
    fun show() {
        val content=column().apply { setPadding(dp(20),dp(4),dp(20),dp(8)) }
        text(activity.getString(R.string.handy_trial_preview_only),content,true)
        val modes=LinearLayout(activity).apply { orientation=LinearLayout.HORIZONTAL };content.addView(modes)
        modeRange=button(R.string.handy_trial_range,modes) { chooseMode("range") }
        modeSequence=button(R.string.handy_trial_sequence,modes) { chooseMode("sequence") }
        listOf(modeRange,modeSequence).forEach {
            it.layoutParams=LinearLayout.LayoutParams(0,-2,1f);it.setBackgroundResource(R.drawable.device_tab);it.backgroundTintList=null
        }
        range=column();sequence=column();content.addView(range);content.addView(sequence)
        input(range,"lower","下限（%）","例：45")
        input(range,"upper","上限（%）","例：55")
        input(range,"half","片道の時間（秒）","例：3.0")
        input(range,"cycles","往復回数","1〜10","1")
        text("開始位置への移動（別途約3秒の指定）の後、上限→下限で1往復します。",range,true)
        input(sequence,"initial","開始位置（%）","例：0")
        input(sequence,"rows","目標位置（%）, 移動時間（秒）","55, 3.0\n45, 3.0",multiline=true)
        input(sequence,"repeats","列の繰り返し","1〜10","1")
        checkButton=button(R.string.handy_trial_check,content) { validate() }
        chart=HandyTrialChart(activity);content.addView(chart,LinearLayout.LayoutParams(-1,dp(145)))
        status=text(activity.getString(R.string.handy_trial_not_checked),content,true)
        status.accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_NONE // Avoid announcing every 100ms diagram tick.
        previewButton=button(R.string.handy_trial_preview,content) {
            if(active) { stopPreview();return@button }
            val p=validate() ?: return@button
            plan=p;previewed=p;previewStatus="途中終了";began=SystemClock.elapsedRealtime();active=true;epoch++
            setEditable(false);previewButton.setText(R.string.handy_trial_end_preview);main.post(ticker)
        }
        previewButton.visibility=View.GONE
        realButton=button(R.string.handy_trial_real,content) {
            if(realState().trialActive) { waitingReal=false;stopReal();return@button }
            val p=validate() ?: return@button
            stopPreview();sentPlan=p;realResult="開始要求のみ（動作は未確認）";sawRealRun=false;waitingReal=true
            requestedAt=SystemClock.elapsedRealtime();setEditable(false);startReal(p)
        }
        val limits=column().apply { visibility=View.GONE }
        button(R.string.handy_trial_limits,content) { limits.visibility=if(limits.visibility==View.GONE) View.VISIBLE else View.GONE }
        content.addView(limits)
        text("位置0〜100%（保存した上限以内・両端1mm余白）。1区間0.25〜20秒、最大20区間・合計120秒。最低速度より遅い指定は早く到着して待機する場合があります。図は入力した予定で、実測位置ではありません。",limits,true)
        input(content,"notes","感想","良かった点、変えたい点",multiline=true).filters=arrayOf(InputFilter.LengthFilter(500))
        val container=column()
        container.addView(ScrollView(activity).apply { addView(content) },LinearLayout.LayoutParams(-1,0,1f))
        button(R.string.global_stop,container) { stopPreview();stopAll() }.apply { setTextColor(0xFFFCA5A5.toInt()) }

        fields.filterKeys { it!="notes" }.values.forEach { edit -> edit.addTextChangedListener(object:TextWatcher {
            override fun beforeTextChanged(s: CharSequence?,start: Int,count: Int,after: Int) {}
            override fun onTextChanged(s: CharSequence?,start: Int,before: Int,count: Int) { if(!active) invalidateInput() }
            override fun afterTextChanged(s: Editable?) {}
        }) }
        dialog=AlertDialog.Builder(activity).setTitle(R.string.handy_trial_title)
            .setView(container)
            .setPositiveButton(R.string.handy_trial_share,null)
            .setNeutralButton(R.string.handy_trial_copy,null)
            .setNegativeButton(R.string.handy_trial_close,null).create()
        dialog.setOnDismissListener { main.removeCallbacks(realTicker);if(waitingReal || realState().trialActive) stopReal();waitingReal=false;stopPreview();save();onClosed() }
        dialog.show()
        dialog.window?.setLayout(-1,(activity.resources.displayMetrics.heightPixels*.9).toInt())
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            val data=export() ?: return@setOnClickListener
            activity.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Handyテスト案",data))
            Toast.makeText(activity,R.string.handy_trial_copied,Toast.LENGTH_SHORT).show()
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val data=export() ?: return@setOnClickListener
            val intent=Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,data)
            activity.startActivity(Intent.createChooser(intent,activity.getString(R.string.handy_trial_share)))
        }
        applyMode();main.post(realTicker)
    }
    private fun value(key: String) = fields.getValue(key).text.toString()
    private fun current() = if(mode=="range") HandyTrialPlan.range(value("lower"),value("upper"),value("half"),value("cycles"))
        else HandyTrialPlan.sequence(value("initial"),value("rows"),value("repeats"))
    private fun validate(): HandyTrialPlan? {
        return try {
            current().also {
                plan=it;chart.plan=it;chart.elapsedMs=0;chart.invalidate()
                status.text=activity.getString(R.string.handy_trial_summary,it.points.size,it.totalMs/1000.0)
            }
        } catch(e: IllegalArgumentException) {
            plan=null;chart.plan=null;chart.invalidate();status.text=e.message;null
        }
    }
    private fun export(): String? {
        stopPreview()
        val p=validate() ?: return null
        save()
        return p.share(value("notes"),if(previewed==p) previewStatus else "未実施",if(sentPlan==p) realResult else null,realState().upperMm)
    }
    private fun chooseMode(next: String) { if(active || waitingReal || realState().trialActive) return;mode=next;applyMode();invalidateInput() }
    private fun applyMode() {
        range.visibility=if(mode=="range") View.VISIBLE else View.GONE
        sequence.visibility=if(mode=="sequence") View.VISIBLE else View.GONE
        modeRange.isSelected=mode=="range";modeSequence.isSelected=mode=="sequence"
    }
    private fun invalidateInput() { plan=null;chart.plan=null;chart.invalidate();status.setText(R.string.handy_trial_not_checked) }
    private fun setEditable(enabled: Boolean) {
        fields.filterKeys { it!="notes" }.values.forEach { it.isEnabled=enabled }
        modeRange.isEnabled=enabled;modeSequence.isEnabled=enabled;checkButton.isEnabled=enabled
    }
    private fun stopPreview() {
        val wasActive=active;active=false;epoch++;main.removeCallbacks(ticker)
        if(::previewButton.isInitialized) { previewButton.setText(R.string.handy_trial_preview);setEditable(true) }
        if(wasActive && ::status.isInitialized) status.setText(R.string.handy_trial_preview_stopped)
    }
    private fun save() {
        val changed=fields.filter { (key,edit)-> original[key]!=edit.text.toString() }
        if(changed.isEmpty() && mode==originalMode) return
        val editor=prefs.edit()
        changed.forEach { (key,edit)->editor.putString(key,edit.text.toString()) }
        if(mode!=originalMode) editor.putString("mode",mode)
        editor.apply()
    }
    fun close() { if(::dialog.isInitialized) dialog.dismiss() }
}

/** Input diagram, not hardware telemetry. */
internal class HandyTrialChart(context: Context): View(context) {
    var plan: HandyTrialPlan?=null
        set(value) {
            field=value
            contentDescription=value?.let { context.getString(R.string.handy_trial_chart_description,it.points.size,it.totalMs/1000.0) }
            invalidate()
        }
    var elapsedMs: Long=0
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val p=plan ?: return
        val scale=resources.displayMetrics.density
        paint.textSize=12*resources.displayMetrics.scaledDensity
        val left=42*scale;val right=width-10*scale;val top=16*scale;val bottom=height-26*scale
        if(right<=left || bottom<=top) return
        val positions=p.points.map { it.percent }+p.initialPercent
        val low=kotlin.math.floor(positions.min()-3).coerceAtLeast(0.0);val high=kotlin.math.ceil(positions.max()+3).coerceAtMost(100.0)
        fun x(time: Long)=left+(right-left)*(time.toDouble()/p.totalMs).toFloat()
        fun y(position: Double)=bottom-(bottom-top)*((position-low)/(high-low)).toFloat()
        paint.color=0xFF9CA3AF.toInt();paint.strokeWidth=scale
        canvas.drawLine(left,bottom,right,bottom,paint)
        canvas.drawText("${HandyTrialPlan.number(high)}%",0f,top+paint.textSize/2,paint)
        canvas.drawText("${HandyTrialPlan.number(low)}%",0f,bottom,paint)
        canvas.drawText("0秒",left,bottom+20*scale,paint)
        val endLabel="${HandyTrialPlan.number(p.totalMs/1000.0)}秒"
        canvas.drawText(endLabel,right-paint.measureText(endLabel),bottom+20*scale,paint)
        val path=Path().apply { moveTo(x(0),y(p.initialPercent)) };var time=0L
        p.points.forEach { time+=it.durationMs;path.lineTo(x(time),y(it.percent)) }
        paint.style=Paint.Style.STROKE;paint.strokeWidth=2*scale;paint.color=0xFFF472B6.toInt();canvas.drawPath(path,paint)
        paint.style=Paint.Style.FILL
        val f=p.frame(elapsedMs)
        canvas.drawCircle(x(f.elapsedMs),y(f.diagramPercent),4*scale,paint)
    }
}
