package com.dsh.kidstv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.min
import kotlin.random.Random

/**
 * 极简赛车：三车道，遥控器 ← → 转向，躲开迎面来的车。
 * 纯 Canvas 绘制，不依赖任何游戏引擎，也不占内存。
 */
class RacingView(ctx: Context) : View(ctx) {

    private enum class State { READY, RUNNING, OVER }

    private var state = State.READY

    // 世界
    private var roadLeft = 0f
    private var roadRight = 0f
    private var laneW = 0f
    private var carW = 0f
    private var carH = 0f

    // 玩家
    private var px = 0f            // 当前车头位置（中心 x）
    private var targetX = 0f       // 转向目标
    private var steer = 0          // -1 / 0 / 1
    private var playerY = 0f

    // 世界状态
    private var worldSpeed = 340f
    private var roadOffset = 0f
    private var score = 0f
    private var best = 0
    private var spawnTimer = 0.8f
    private var lastNanos = 0L

    private class Enemy(var x: Float, var y: Float, val color: Int, val speed: Float)

    private val enemies = ArrayList<Enemy>()
    private val rnd = Random(System.nanoTime())

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }

    private val carColors = intArrayOf(Palette.leaf, Palette.sun, Palette.sky, Palette.plum)

    private val ticker = object : Runnable {
        override fun run() {
            step()
            invalidate()
            if (isShown) postOnAnimation(this)
        }
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(0xFF1B2027.toInt())
        best = prefs().getInt("best", 0)
    }

    private fun prefs() = context.getSharedPreferences("race", Context.MODE_PRIVATE)

    private fun dp(v: Float) = v * resources.displayMetrics.density

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val roadW = min(w * 0.72f, h * 1.05f)
        roadLeft = (w - roadW) / 2f
        roadRight = roadLeft + roadW
        laneW = roadW / 3f
        // 车宽跟车道走，但不能超过屏幕高度的 17%，否则在宽屏上会大得离谱
        carH = min(laneW * 0.62f * 1.5f, h * 0.17f)
        carW = carH / 1.5f
        playerY = h - carH * 1.5f
        if (state == State.READY) {
            px = (roadLeft + roadRight) / 2f
            targetX = px
        }
        px = px.coerceIn(roadLeft + carW / 2, roadRight - carW / 2)
        targetX = targetX.coerceIn(roadLeft + carW / 2, roadRight - carW / 2)
    }

    // ---------------- 状态 ----------------

    private fun start() {
        state = State.RUNNING
        score = 0f
        worldSpeed = 340f
        spawnTimer = 1.1f
        enemies.clear()
        px = (roadLeft + roadRight) / 2f
        targetX = px
        steer = 0
        lastNanos = 0L
        if (!isShown) return
        removeCallbacks(ticker)
        postOnAnimation(ticker)
    }

    private fun crash() {
        state = State.OVER
        steer = 0
        val s = score.toInt()
        if (s > best) {
            best = s
            prefs().edit().putInt("best", s).apply()
        }
    }

    private fun step() {
        val now = System.nanoTime()
        val dt = if (lastNanos == 0L) 0f else ((now - lastNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
        lastNanos = now
        if (state != State.RUNNING) return

        // 转向：目标位置移动 + 车身平滑跟随，松手不会瞬移
        val steerSpeed = laneW * 2.6f
        targetX += steer * steerSpeed * dt
        val minX = roadLeft + carW / 2
        val maxX = roadRight - carW / 2
        targetX = targetX.coerceIn(minX, maxX)
        px += (targetX - px) * min(1f, dt * 14f)

        // 速度随分数缓慢提升
        worldSpeed = (340f + score * 1.6f).coerceAtMost(1150f)
        score += worldSpeed * dt * 0.08f
        roadOffset = (roadOffset + worldSpeed * dt) % (carH * 2f)

        // 出车
        spawnTimer -= dt
        if (spawnTimer <= 0f) {
            spawn()
            spawnTimer = (1.35f - score * 0.0014f).coerceIn(0.62f, 1.35f)
        }

        // 迎面车下移
        val it2 = enemies.iterator()
        while (it2.hasNext()) {
            val e = it2.next()
            e.y += (worldSpeed - e.speed) * dt
            if (e.y > height + carH) it2.remove()
        }

        // 碰撞
        val pw = carW * 0.78f
        val ph = carH * 0.86f
        for (e in enemies) {
            val ew = carW * 0.78f
            val eh = carH * 0.86f
            if (abs(e.x - px) < (pw + ew) / 2f && abs(e.y - playerY) < (ph + eh) / 2f) {
                crash()
                break
            }
        }
    }

    private fun spawn() {
        if (score < 12f) return // 刚起步先给玩家一点反应时间
        val lane = rnd.nextInt(3)
        val x = roadLeft + laneW * (lane + 0.5f)
        // 不要一次把三条道全堵死：若最近的车太靠上，换了道也没用，就直接不出
        val speed = worldSpeed * (0.45f + rnd.nextFloat() * 0.25f)
        for (e in enemies) if (e.y < carH * 2.2f && abs(e.x - x) < laneW * 0.5f) return
        enemies.add(Enemy(x, -carH, carColors[rnd.nextInt(carColors.size)], speed))
    }

    // ---------------- 输入 ----------------

    /** 遥控器多是「点一下」而不是长按，点一下也给一格位移 */
    private fun tapSteer(dir: Int) {
        if (state != State.RUNNING) return
        val minX = roadLeft + carW / 2
        val maxX = roadRight - carW / 2
        targetX = (targetX + dir * laneW * 0.62f).coerceIn(minX, maxX)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                steer = -1
                tapSteer(-1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                steer = 1
                tapSteer(1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A -> {
                if (state != State.RUNNING) start()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            steer = 0
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (state != State.RUNNING) { start(); return true }
                targetX = event.x
            }
            MotionEvent.ACTION_MOVE -> targetX = event.x
        }
        return true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestFocus()
        postOnAnimation(ticker)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    // ---------------- 绘制 ----------------

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        // 两侧草地
        paint.color = 0xFF232A31.toInt()
        canvas.drawRect(0f, 0f, w, h, paint)
        // 路面
        paint.color = 0xFF2E343D.toInt()
        canvas.drawRect(roadLeft, 0f, roadRight, h, paint)
        // 路肩
        paint.color = 0xFFE8E2D6.toInt()
        canvas.drawRect(roadLeft - dp(4f), 0f, roadLeft, h, paint)
        canvas.drawRect(roadRight, 0f, roadRight + dp(4f), h, paint)

        // 路肩标线（跟着速度滚，速度感全靠它）
        paint.color = 0xFFE85C43.toInt()
        val markH = carH * 0.30f
        val markGap = carH * 0.62f
        var my = -markGap + (roadOffset * 1.25f) % (markH + markGap)
        while (my < h) {
            canvas.drawRect(roadLeft - dp(9f), my, roadLeft - dp(4f), my + markH, paint)
            canvas.drawRect(roadRight + dp(4f), my, roadRight + dp(9f), my + markH, paint)
            my += markH + markGap
        }

        // 车道虚线
        paint.color = 0xFFF2EEE4.toInt()
        val dash = carH * 0.55f
        val gap = carH * 0.55f
        var y = -gap + roadOffset % (dash + gap)
        while (y < h) {
            canvas.drawRect(roadLeft + laneW - dp(2.5f), y, roadLeft + laneW + dp(2.5f), y + dash, paint)
            canvas.drawRect(roadLeft + laneW * 2 - dp(2.5f), y, roadLeft + laneW * 2 + dp(2.5f), y + dash, paint)
            y += dash + gap
        }

        // 迎面车
        for (e in enemies) drawCar(canvas, e.x, e.y, e.color, false)
        // 玩家车
        if (state != State.READY) drawCar(canvas, px, playerY, Palette.coral, true)

        drawHud(canvas, w)

        when (state) {
            State.READY -> drawCover(canvas, w, h, "赛车", "按 OK 出发")
            State.OVER -> drawCover(canvas, w, h, "撞车了", "跑了 ${score.toInt()} 米 · 按 OK 再来")
            State.RUNNING -> Unit
        }
    }

    private fun drawCar(canvas: Canvas, cx: Float, cy: Float, color: Int, player: Boolean) {
        val hw = carW / 2f
        val hh = carH / 2f
        val body = RectF(cx - hw, cy - hh, cx + hw, cy + hh)

        // 影子
        paint.color = 0x33000000
        canvas.drawRoundRect(
            RectF(body.left + dp(3f), body.top + dp(5f), body.right + dp(3f), body.bottom + dp(5f)),
            dp(10f), dp(10f), paint
        )
        // 车身
        paint.color = color
        canvas.drawRoundRect(body, dp(10f), dp(10f), paint)
        // 车窗
        paint.color = if (player) 0xFFCBE6F2.toInt() else 0xFFB9C6D2.toInt()
        canvas.drawRoundRect(
            RectF(cx - hw * 0.62f, cy - hh * 0.42f, cx + hw * 0.62f, cy + hh * 0.02f),
            dp(6f), dp(6f), paint
        )
        // 前挡风
        paint.color = 0xFF9FB6C6.toInt()
        canvas.drawRoundRect(
            RectF(cx - hw * 0.55f, cy + hh * 0.16f, cx + hw * 0.55f, cy + hh * 0.52f),
            dp(5f), dp(5f), paint
        )
        // 轮子
        paint.color = 0xFF16191D.toInt()
        val wW = hw * 0.22f
        val wH = hh * 0.26f
        for (sx in intArrayOf(-1, 1)) {
            val x = if (sx < 0) body.left - wW * 0.35f else body.right - wW * 0.65f
            canvas.drawRoundRect(RectF(x, cy - hh * 0.62f, x + wW, cy - hh * 0.62f + wH), dp(3f), dp(3f), paint)
            canvas.drawRoundRect(RectF(x, cy + hh * 0.36f, x + wW, cy + hh * 0.36f + wH), dp(3f), dp(3f), paint)
        }
    }

    private fun drawHud(canvas: Canvas, w: Float) {
        text.color = 0xFFF7EDDC.toInt()
        text.textAlign = Paint.Align.LEFT
        text.textSize = dp(22f)
        canvas.drawText("${score.toInt()} 米", dp(28f), dp(46f), text)
        text.textAlign = Paint.Align.RIGHT
        text.textSize = dp(16f)
        text.color = 0xFF9FB0BF.toInt()
        canvas.drawText("最高 $best", w - dp(28f), dp(44f), text)

        if (state == State.RUNNING) {
            text.textAlign = Paint.Align.CENTER
            text.textSize = dp(13f)
            text.color = 0x99F7EDDC.toInt()
            canvas.drawText("←  →  转向", w / 2f, height - dp(18f), text)
        }
    }

    private fun drawCover(canvas: Canvas, w: Float, h: Float, title: String, sub: String) {
        paint.color = 0xCC12161B.toInt()
        canvas.drawRect(0f, 0f, w, h, paint)

        text.textAlign = Paint.Align.CENTER
        text.color = 0xFFF7EDDC.toInt()
        text.textSize = dp(40f)
        canvas.drawText(title, w / 2f, h / 2f - dp(8f), text)

        text.textSize = dp(16f)
        text.color = 0xFFF0B03C.toInt()
        canvas.drawText(sub, w / 2f, h / 2f + dp(34f), text)

        text.textSize = dp(12f)
        text.color = 0x88F7EDDC.toInt()
        canvas.drawText("返回键回娱乐频道", w / 2f, h / 2f + dp(70f), text)
    }
}
