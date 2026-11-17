package com.dsh.kidstv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.View
import android.widget.TextView

/**
 * 深色影院风：近黑底 + 浅色字 + 珊瑚强调。
 * 命名沿用旧的（paper = 页面背景，ink = 主文字），这样改动最小：
 * 反相后「ink 当文字、paper 当背景」的旧代码自动成立，
 * 「ink 当背景」的少数几处（预览遮罩、绘本阅读器）已单独改成 bg。
 */
object Palette {
    const val bg = 0xFF0E0F11.toInt()
    const val paper = bg
    const val paper2 = 0xFF2A2E34.toInt()
    const val card = 0xFF1A1C1F.toInt()
    const val ink = 0xFFF2F4F7.toInt()
    const val ink2 = 0xFFA8B0BA.toInt()
    const val ink3 = 0xFF6E7681.toInt()
    const val coral = 0xFFE85C43.toInt()
    /** 强调色上的文字（深色） */
    const val onAccent = 0xFF101114.toInt()
    const val leaf = 0xFF2F8F6F.toInt()
    const val sun = 0xFFF0B03C.toInt()
    const val sky = 0xFF4E9BC9.toInt()
    const val plum = 0xFF8C5AA8.toInt()
    private val covers = intArrayOf(coral, leaf, sun, sky, plum)
    fun cover(i: Int): Int = covers[Math.floorMod(i, covers.size)]
}

/** 关掉系统默认的焦点高亮框，不然会和自绘描边叠成双层 */
fun View.noDefaultFocusRing() {
    if (android.os.Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
}

fun Context.dp(v: Number): Int = (v.toFloat() * resources.displayMetrics.density + 0.5f).toInt()

fun Context.sp(v: Number): Float = TypedValue.applyDimension(
    TypedValue.COMPLEX_UNIT_SP, v.toFloat(), resources.displayMetrics
)

/**
 * 标题/强调用的字体。
 * 以前打包的是「站酷快乐体」（ZCOOL KuaiLe）—— 那是手写体，笔画本身带倾斜，
 * 看着像斜体。现在统一用系统无衬线中黑，顺带把 1.4MB 的字体文件从包里去掉。
 */
object Type {
    private val face: Typeface by lazy {
        Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    fun display(@Suppress("UNUSED_PARAMETER") ctx: Context): Typeface = face
}

fun shape(
    color: Int,
    radius: Float,
    strokeW: Int = 0,
    strokeColor: Int = Color.TRANSPARENT,
    radii: FloatArray? = null
): GradientDrawable {
    val d = GradientDrawable()
    d.shape = GradientDrawable.RECTANGLE
    d.setColor(color)
    if (radii != null) d.cornerRadii = radii else d.cornerRadius = radius
    if (strokeW > 0) d.setStroke(strokeW, strokeColor)
    return d
}

fun Context.label(
    text: String,
    sizeSp: Number,
    color: Int = Palette.ink,
    display: Boolean = false,
    weight: Int = Typeface.NORMAL
): TextView = TextView(this).apply {
    this.text = text
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp.toFloat())
    setTextColor(color)
    typeface = if (display) Type.display(this@label) else Typeface.create("sans-serif", weight)
    includeFontPadding = false
}

/** 桌面背景：暖纸色 + 极淡的网格圆点，像一本练习本。 */
class DottedPaperView(ctx: Context) : View(ctx) {
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x14FFFFFF
        style = Paint.Style.FILL
    }
    private val step = ctx.dp(48)
    private val r = ctx.dp(1.5f).toFloat()

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Palette.paper)
        var y = step / 2f
        while (y < height) {
            var x = step / 2f
            while (x < width) {
                canvas.drawCircle(x, y, r, dot)
                x += step
            }
            y += step
        }
    }
}
