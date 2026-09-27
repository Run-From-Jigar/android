package com.versatile.runfromjigar

import android.content.Context
import android.content.SharedPreferences
import android.graphics.*
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

// =====================================================================
//  GameView — the whole game in one Canvas-driven SurfaceView.
// =====================================================================
//  Mirrors the mechanics of the iOS/SpriteKit build:
//   • 3 lanes, swipe left/right to switch, swipe up to use a power-up
//   • The virus ("Jigar") chases from below — a vertical Health bar,
//     top-right, 0-100, drains over time
//   • The virus fires ranged shots with a telegraph warning; a hit
//     chips 4-15 off the Health bar
//   • Touching the virus directly is instant death, independent of
//     the Health bar
//   • RAM chips fall down the lanes; collect 10 for a random power-up
//
//  No game engine, no XML layouts for gameplay — everything is drawn
//  each frame with android.graphics.Canvas. Simple and dependency-free,
//  which is why it needs nothing beyond the plain Android SDK to build.
// =====================================================================

private enum class GameState { MENU, PLAYING, GAME_OVER }

private enum class PowerUp(val label: String, val color: Int) {
    SPEED_BOOST("SPEED BOOST", Color.CYAN),
    RAM_MAGNET("RAM MAGNET", Color.GREEN),
    SHIELD("SHIELD", Color.rgb(90, 140, 255)),
    JIGAR_ATTACK("JIGAR ATTACK", Color.RED),
    FREEZE_JIGAR("FREEZE JIGAR", Color.rgb(190, 100, 255))
}

private data class RamChip(var x: Float, var y: Float, var lane: Int)
private data class Projectile(var x: Float, var y: Float)

class GameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    // MARK: - Persistence (mirrors MetaProgress from the iOS build)

    private val prefs: SharedPreferences =
        context.getSharedPreferences("RunFromJigarProgress", Context.MODE_PRIVATE)

    private var totalRam: Int
        get() = prefs.getInt("totalRam", 0)
        set(value) = prefs.edit().putInt("totalRam", value).apply()

    private var bestRun: Int
        get() = prefs.getInt("bestRun", 0)
        set(value) = prefs.edit().putInt("bestRun", value).apply()

    private val level: Int get() = totalRam / 100 + 1

    // MARK: - Game loop thread

    private var gameThread: GameThread? = null
    private val holderCallback = this

    private inner class GameThread(private val surfaceHolder: SurfaceHolder) : Thread() {
        @Volatile var running = false
        private var lastTime = System.nanoTime()

        override fun run() {
            while (running) {
                val now = System.nanoTime()
                val dt = ((now - lastTime) / 1_000_000_000.0).toFloat().coerceAtMost(0.05f)
                lastTime = now

                update(dt)

                val canvas = surfaceHolder.lockCanvas() ?: continue
                try {
                    drawGame(canvas)
                } finally {
                    surfaceHolder.unlockCanvasAndPost(canvas)
                }

                // Cap roughly to 60fps without hogging the CPU.
                sleep(6)
            }
        }
    }

    // MARK: - Screen size / layout

    private var screenW = 0f
    private var screenH = 0f
    private var lanes = floatArrayOf(-100f, 0f, 100f)
    private var contentScale = 1f
    private var playerBaseY = 0f
    private var maxVisualGap = 260f

    // MARK: - State

    private var state = GameState.MENU

    private var ramCount = 0
    private var ramNeededForPowerUp = 10
    private var jigarHealth = 100f
    private var jigarFrozen = false
    private var jigarShielded = false
    private var powerUpActive = false
    private var availablePowerUp: PowerUp? = null

    private var currentLane = 1
    private var playerX = 0f
    private var playerY = 0f
    private var jigarX = 0f
    private var jigarY = 0f

    private val ramChips = mutableListOf<RamChip>()
    private val projectiles = mutableListOf<Projectile>()

    private var ramSpawnTimer = 0f
    private var jigarShotTimer = Random.nextFloat() * 2.5f + 2.5f
    private var warningTimer = -1f       // >=0 while a shot is telegraphed
    private var warningLaneX = 0f
    private var shieldTimer = 0f
    private var freezeTimer = 0f
    private var speedTimer = 0f
    private var gameOverReason = ""
    private var lastDamageShown = 0
    private var damageFlashTimer = 0f

    // MARK: - Paint objects (reused every frame — never allocate in draw())

    private val bgPaint = Paint().apply { color = Color.rgb(5, 5, 15) }
    private val lanePaint = Paint().apply {
        color = Color.argb(25, 40, 255, 255)
        strokeWidth = 4f
    }
    private val playerPaint = Paint().apply { color = Color.rgb(90, 240, 255); isAntiAlias = true }
    private val playerGlowPaint = Paint().apply {
        color = Color.argb(70, 90, 240, 255)
        isAntiAlias = true
        maskFilter = BlurMaskFilter(30f, BlurMaskFilter.Blur.NORMAL)
    }
    private val virusBodyPaint = Paint().apply { color = Color.rgb(140, 20, 30); isAntiAlias = true }
    private val virusSpikePaint = Paint().apply {
        color = Color.rgb(190, 40, 50)
        isAntiAlias = true
        strokeWidth = 5f
    }
    private val virusGlowPaint = Paint().apply {
        color = Color.argb(80, 220, 30, 30)
        isAntiAlias = true
        maskFilter = BlurMaskFilter(34f, BlurMaskFilter.Blur.NORMAL)
    }
    private val eyePaint = Paint().apply { color = Color.rgb(255, 40, 40); isAntiAlias = true }
    private val eyeHighlightPaint = Paint().apply { color = Color.WHITE; isAntiAlias = true; alpha = 220 }
    private val ramPaint = Paint().apply { color = Color.rgb(40, 160, 70); isAntiAlias = true }
    private val ramStrokePaint = Paint().apply {
        color = Color.rgb(120, 255, 140); style = Paint.Style.STROKE; strokeWidth = 3f; isAntiAlias = true
    }
    private val projectilePaint = Paint().apply { color = Color.rgb(255, 60, 60); isAntiAlias = true }
    private val warningPaint = Paint().apply {
        color = Color.rgb(255, 160, 0); textAlign = Paint.Align.CENTER; isAntiAlias = true
    }
    private val textPaint = Paint().apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER; isAntiAlias = true
        typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
    }
    private val barBackPaint = Paint().apply { color = Color.argb(60, 255, 255, 255) }
    private val barFillPaint = Paint().apply { color = Color.GREEN }

    // MARK: - Init

    init {
        holder.addCallback(holderCallback)
        isFocusable = true
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        screenW = width.toFloat()
        screenH = height.toFloat()
        computeLayout()
        gameThread = GameThread(holder).also {
            it.running = true
            it.start()
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
        screenW = w.toFloat()
        screenH = h.toFloat()
        computeLayout()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        gameThread?.running = false
        gameThread?.join()
        gameThread = null
    }

    fun pauseGame() {
        gameThread?.running = false
    }

    fun resumeGame() {
        if (gameThread == null && holder.surface.isValid) {
            gameThread = GameThread(holder).also {
                it.running = true
                it.start()
            }
        }
    }

    /// Lanes span most of the width, and every gameplay sprite scales
    /// with the actual screen size — same approach as the iOS build,
    /// so this looks right on a phone or a big tablet.
    private fun computeLayout() {
        val laneSpan = screenW * 0.32f
        lanes = floatArrayOf(-laneSpan, 0f, laneSpan)
        contentScale = (screenW / 1080f).coerceIn(1f, 2.4f) // 1080px ~ a typical phone width
        maxVisualGap = 260f * contentScale * 3f  // *3 to feel right at pixel (not point) scale
        playerBaseY = screenH * 0.72f
        playerX = screenW / 2 + lanes[currentLane]
        playerY = playerBaseY
        jigarX = playerX
        jigarY = playerBaseY - maxVisualGap
    }

    private fun laneCenterX(lane: Int) = screenW / 2 + lanes[lane]

    // MARK: - Update

    private fun update(dt: Float) {
        when (state) {
            GameState.MENU, GameState.GAME_OVER -> { /* idle, waiting for input */ }
            GameState.PLAYING -> updatePlaying(dt)
        }
    }

    private fun updatePlaying(dt: Float) {
        // Smoothly slide toward the current lane.
        val targetX = laneCenterX(currentLane)
        playerX += (targetX - playerX) * (dt * 10f).coerceAtMost(1f)

        if (shieldTimer > 0) { shieldTimer -= dt; if (shieldTimer <= 0) jigarShielded = false }
        if (freezeTimer > 0) { freezeTimer -= dt; if (freezeTimer <= 0) jigarFrozen = false }
        if (speedTimer > 0) { speedTimer -= dt; if (speedTimer <= 0) powerUpActive = false }
        if (damageFlashTimer > 0) damageFlashTimer -= dt

        // --- Health drain (the chase) ---
        if (!jigarFrozen) {
            jigarHealth -= Random.nextFloat() * 1.6f * dt * 10f
            jigarHealth += 0.3f * dt * 10f // small breathing room, same feel as iOS build
        }
        jigarHealth = jigarHealth.coerceIn(0f, 100f)

        val visualGap = (jigarHealth / 100f) * maxVisualGap
        val targetJigarX = playerX
        val targetJigarY = playerBaseY - visualGap
        jigarX += (targetJigarX - jigarX) * (dt * 8f).coerceAtMost(1f)
        jigarY += (targetJigarY - jigarY) * (dt * 8f).coerceAtMost(1f)

        if (jigarHealth <= 0f) {
            if (jigarShielded) {
                jigarHealth = 20f
            } else {
                endGame("JIGAR CAUGHT YOU")
                return
            }
        }

        // --- Direct touch = instant death ---
        val touchDist = distance(playerX, playerY, jigarX, jigarY)
        val virusRadius = 34f * contentScale
        val playerRadius = 26f * contentScale
        if (touchDist < virusRadius + playerRadius) {
            if (jigarShielded) {
                jigarHealth = jigarHealth.coerceAtLeast(20f)
            } else {
                endGame("THE VIRUS GOT YOU")
                return
            }
        }

        // --- RAM spawning ---
        ramSpawnTimer -= dt
        if (ramSpawnTimer <= 0f) {
            ramSpawnTimer = 0.85f
            val lane = Random.nextInt(3)
            ramChips.add(RamChip(laneCenterX(lane), -40f, lane))
        }
        val ramIter = ramChips.iterator()
        while (ramIter.hasNext()) {
            val chip = ramIter.next()
            chip.y += (screenH / 3.2f) * dt
            if (chip.y > screenH + 40f) {
                ramIter.remove()
                continue
            }
            val d = distance(playerX, playerY, chip.x, chip.y)
            if (d < playerRadius + 16f * contentScale) {
                ramIter.remove()
                collectRam()
            }
        }

        // --- Jigar ranged attack ---
        if (!jigarFrozen) {
            if (warningTimer >= 0f) {
                warningTimer -= dt
                if (warningTimer <= 0f) {
                    warningTimer = -1f
                    projectiles.add(Projectile(warningLaneX, jigarY - 20f * contentScale))
                }
            } else {
                jigarShotTimer -= dt
                if (jigarShotTimer <= 0f) {
                    jigarShotTimer = Random.nextFloat() * 2.5f + 2.5f
                    warningTimer = 0.45f
                    warningLaneX = jigarX
                }
            }
        }
        val projIter = projectiles.iterator()
        while (projIter.hasNext()) {
            val p = projIter.next()
            p.y -= (screenH / 1.1f) * dt
            if (p.y < -40f) {
                projIter.remove()
                continue
            }
            val d = distance(playerX, playerY, p.x, p.y)
            if (d < playerRadius + 12f * contentScale) {
                projIter.remove()
                handleProjectileHit()
            }
        }

        // Power-up availability.
        if (availablePowerUp == null && ramCount >= ramNeededForPowerUp) {
            availablePowerUp = PowerUp.entries.random()
            ramNeededForPowerUp += 10
        }
    }

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun collectRam() {
        ramCount += 1
        jigarHealth = (jigarHealth + 0.5f).coerceAtMost(100f)
    }

    private fun handleProjectileHit() {
        if (jigarShielded) return
        val damage = Random.nextInt(4, 16) // 4...15 inclusive
        jigarHealth = (jigarHealth - damage).coerceAtLeast(0f)
        lastDamageShown = damage
        damageFlashTimer = 0.6f
        if (jigarHealth <= 0f) {
            endGame("JIGAR CAUGHT YOU")
        }
    }

    private fun usePowerUp() {
        val powerUp = availablePowerUp ?: return
        if (powerUpActive) return
        availablePowerUp = null
        powerUpActive = true

        when (powerUp) {
            PowerUp.SPEED_BOOST -> speedTimer = 5f
            PowerUp.RAM_MAGNET -> {
                for (chip in ramChips) { chip.x = playerX }
                powerUpActive = false
            }
            PowerUp.SHIELD -> {
                jigarShielded = true
                shieldTimer = 6f
                powerUpActive = false
            }
            PowerUp.JIGAR_ATTACK -> {
                jigarHealth = (jigarHealth + 25f).coerceAtMost(100f)
                powerUpActive = false
            }
            PowerUp.FREEZE_JIGAR -> {
                jigarFrozen = true
                freezeTimer = 5f
                powerUpActive = false
            }
        }
    }

    private fun endGame(reason: String) {
        gameOverReason = reason
        totalRam += ramCount
        if (ramCount > bestRun) bestRun = ramCount
        state = GameState.GAME_OVER
    }

    private fun startGame() {
        ramCount = 0
        ramNeededForPowerUp = 10
        jigarHealth = 100f
        jigarFrozen = false
        jigarShielded = false
        powerUpActive = false
        availablePowerUp = null
        currentLane = 1
        ramChips.clear()
        projectiles.clear()
        ramSpawnTimer = 0f
        jigarShotTimer = Random.nextFloat() * 2.5f + 2.5f
        warningTimer = -1f
        playerX = laneCenterX(currentLane)
        playerY = playerBaseY
        jigarX = playerX
        jigarY = playerBaseY - maxVisualGap
        state = GameState.PLAYING
    }

    // MARK: - Drawing

    private fun drawGame(canvas: Canvas) {
        canvas.drawRect(0f, 0f, screenW, screenH, bgPaint)
        drawLanes(canvas)

        when (state) {
            GameState.MENU -> drawMenu(canvas)
            GameState.PLAYING -> drawPlaying(canvas)
            GameState.GAME_OVER -> drawGameOver(canvas)
        }
    }

    private fun drawLanes(canvas: Canvas) {
        for (lane in lanes) {
            val x = screenW / 2 + lane
            canvas.drawLine(x, 0f, x, screenH, lanePaint)
        }
    }

    private fun drawMenu(canvas: Canvas) {
        textPaint.textSize = 64f * contentScale
        canvas.drawText("RUN FROM JIGAR", screenW / 2, screenH * 0.3f, textPaint)

        textPaint.textSize = 28f * contentScale
        textPaint.color = Color.GREEN
        canvas.drawText("STEAL BACK THE RAM", screenW / 2, screenH * 0.36f, textPaint)
        textPaint.color = Color.WHITE

        textPaint.textSize = 32f * contentScale
        canvas.drawText("LEVEL $level   ·   TOTAL RAM $totalRam   ·   BEST $bestRun",
            screenW / 2, screenH * 0.48f, textPaint)

        textPaint.textSize = 40f * contentScale
        textPaint.color = Color.rgb(60, 220, 100)
        canvas.drawText("▶  TAP TO PLAY", screenW / 2, screenH * 0.62f, textPaint)
        textPaint.color = Color.WHITE

        textPaint.textSize = 22f * contentScale
        textPaint.alpha = 180
        canvas.drawText("Swipe left/right: change lane   ·   Swipe up: use power-up",
            screenW / 2, screenH * 0.7f, textPaint)
        canvas.drawText("Touching the virus = instant death. Dodge its ranged shots (4-15 dmg).",
            screenW / 2, screenH * 0.74f, textPaint)
        textPaint.alpha = 255
    }

    private fun drawPlaying(canvas: Canvas) {
        // RAM chips
        for (chip in ramChips) drawRamChip(canvas, chip.x, chip.y)

        // Projectiles
        for (p in projectiles) canvas.drawCircle(p.x, p.y, 11f * contentScale, projectilePaint)

        // Warning telegraph
        if (warningTimer >= 0f) {
            warningPaint.textSize = 36f * contentScale
            warningPaint.alpha = if ((warningTimer * 10).toInt() % 2 == 0) 255 else 90
            canvas.drawText("⚠", warningLaneX, screenH * 0.18f, warningPaint)
        }

        drawVirus(canvas)
        drawPlayer(canvas)

        // --- HUD ---
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = 30f * contentScale
        textPaint.color = Color.WHITE
        canvas.drawText("RAM  $ramCount / $ramNeededForPowerUp", 24f, 70f, textPaint)
        textPaint.textAlign = Paint.Align.CENTER

        drawHealthBar(canvas)

        availablePowerUp?.let { powerUp ->
            textPaint.textSize = 26f * contentScale
            textPaint.color = powerUp.color
            canvas.drawText("⚡ ${powerUp.label} — swipe ↑", screenW / 2, screenH - 60f, textPaint)
            textPaint.color = Color.WHITE
        }

        if (damageFlashTimer > 0) {
            textPaint.textSize = 26f * contentScale
            textPaint.color = Color.RED
            canvas.drawText("-$lastDamageShown", screenW - 90f * contentScale, 140f * contentScale, textPaint)
            textPaint.color = Color.WHITE
        }
    }

    private fun drawHealthBar(canvas: Canvas) {
        val barW = 22f * contentScale
        val barH = 220f * contentScale
        val x = screenW - 70f * contentScale
        val topY = 120f * contentScale

        textPaint.textSize = 22f * contentScale
        textPaint.color = Color.RED
        canvas.drawText("JIGAR", x, topY - 12f, textPaint)
        textPaint.color = Color.WHITE

        canvas.drawRect(x - barW / 2, topY, x + barW / 2, topY + barH, barBackPaint)

        val healthFrac = (jigarHealth / 100f).coerceIn(0f, 1f)
        val filledH = barH * healthFrac
        barFillPaint.color = healthBarColor(1 - healthFrac)
        canvas.drawRect(x - barW / 2, topY + (barH - filledH), x + barW / 2, topY + barH, barFillPaint)

        canvas.drawText("${jigarHealth.toInt()}", x, topY + barH + 30f, textPaint)
    }

    private fun healthBarColor(danger: Float): Int {
        return if (danger < 0.5f) {
            blend(Color.rgb(70, 230, 90), Color.rgb(240, 210, 40), danger / 0.5f)
        } else {
            blend(Color.rgb(240, 210, 40), Color.rgb(240, 50, 60), (danger - 0.5f) / 0.5f)
        }
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        val tt = t.coerceIn(0f, 1f)
        val r = (Color.red(a) + (Color.red(b) - Color.red(a)) * tt).toInt()
        val g = (Color.green(a) + (Color.green(b) - Color.green(a)) * tt).toInt()
        val bl = (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * tt).toInt()
        return Color.rgb(r, g, bl)
    }

    private fun drawPlayer(canvas: Canvas) {
        canvas.drawCircle(playerX, playerY, 46f * contentScale, playerGlowPaint)
        canvas.drawCircle(playerX, playerY, 26f * contentScale, playerPaint)
        // Visor stripe so it reads as "facing forward".
        val visorPaint = Paint().apply { color = Color.WHITE; alpha = 220; isAntiAlias = true }
        canvas.drawRect(
            playerX - 12f * contentScale, playerY - 4f * contentScale,
            playerX + 12f * contentScale, playerY + 4f * contentScale, visorPaint
        )
    }

    private fun drawVirus(canvas: Canvas) {
        val bodyRadius = 34f * contentScale
        canvas.drawCircle(jigarX, jigarY, bodyRadius + 20f, virusGlowPaint)

        // Spike proteins ringing the body.
        val spikeCount = 12
        val spikeLength = 16f * contentScale
        for (i in 0 until spikeCount) {
            val angle = (i.toFloat() / spikeCount) * (2 * Math.PI).toFloat()
            val baseX = jigarX + cos(angle) * bodyRadius
            val baseY = jigarY + sin(angle) * bodyRadius
            val tipX = jigarX + cos(angle) * (bodyRadius + spikeLength)
            val tipY = jigarY + sin(angle) * (bodyRadius + spikeLength)
            canvas.drawLine(baseX, baseY, tipX, tipY, virusSpikePaint)
            canvas.drawCircle(tipX, tipY, 6f * contentScale, virusSpikePaint)
        }

        canvas.drawCircle(jigarX, jigarY, bodyRadius, virusBodyPaint)

        // Glowing eyes.
        for (dx in intArrayOf(-1, 1)) {
            val ex = jigarX + dx * 12f * contentScale
            val ey = jigarY + 4f * contentScale
            canvas.drawCircle(ex, ey, 6f * contentScale, eyePaint)
            canvas.drawCircle(ex, ey, 2.5f * contentScale, eyeHighlightPaint)
        }
    }

    private fun drawRamChip(canvas: Canvas, x: Float, y: Float) {
        val half = 14f * contentScale
        canvas.drawRect(x - half, y - half, x + half, y + half, ramPaint)
        canvas.drawRect(x - half, y - half, x + half, y + half, ramStrokePaint)
    }

    private fun drawGameOver(canvas: Canvas) {
        textPaint.textSize = 46f * contentScale
        textPaint.color = Color.RED
        canvas.drawText(gameOverReason, screenW / 2, screenH * 0.35f, textPaint)
        textPaint.color = Color.WHITE

        textPaint.textSize = 32f * contentScale
        canvas.drawText("RAM COLLECTED: $ramCount", screenW / 2, screenH * 0.45f, textPaint)

        textPaint.textSize = 24f * contentScale
        textPaint.color = Color.CYAN
        canvas.drawText("TOTAL BANKED: $totalRam", screenW / 2, screenH * 0.51f, textPaint)
        textPaint.color = Color.WHITE

        canvas.drawText("BEST RUN: $bestRun", screenW / 2, screenH * 0.56f, textPaint)

        textPaint.textSize = 30f * contentScale
        textPaint.color = Color.rgb(60, 220, 100)
        canvas.drawText("TAP TO RUN AGAIN", screenW / 2, screenH * 0.68f, textPaint)
        textPaint.color = Color.WHITE
    }

    // MARK: - Input

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            when (state) {
                GameState.MENU -> startGame()
                GameState.GAME_OVER -> startGame()
                GameState.PLAYING -> { /* no tap actions mid-run yet */ }
            }
            return true
        }

        override fun onFling(
            e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float
        ): Boolean {
            if (state != GameState.PLAYING || e1 == null) return false
            val dx = e2.x - e1.x
            val dy = e2.y - e1.y

            if (abs(dy) > abs(dx) && dy < -120) {
                // Swipe up.
                if (availablePowerUp != null) usePowerUp()
                return true
            }
            if (abs(dx) > 120 && abs(dx) > abs(dy)) {
                if (dx < 0 && currentLane > 0) currentLane -= 1
                if (dx > 0 && currentLane < 2) currentLane += 1
                return true
            }
            return false
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        return true
    }
}
