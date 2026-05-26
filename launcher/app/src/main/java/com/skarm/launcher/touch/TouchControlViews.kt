package com.skarm.launcher.touch

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.skarm.launcher.GameActivity
import com.skarm.launcher.NativeBridge
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

abstract class BaseTouchControl(context: Context, val node: ControlNode) : View(context) {
    var inEditMode: Boolean = false
    var isSelectedNode: Boolean = false

    private val editPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f, 10f), 0f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (inEditMode) {
            editPaint.color = if (isSelectedNode) Color.YELLOW else Color.GRAY
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), editPaint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (inEditMode) return false // Overlay handles edit touches
        return handleGameTouch(event)
    }

    abstract fun handleGameTouch(event: MotionEvent): Boolean
}

class TouchJoystickView(context: Context, node: ControlNode) : BaseTouchControl(context, node) {
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.DKGRAY
        style = Paint.Style.FILL
        alpha = 100
    }
    
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.LTGRAY
        style = Paint.Style.FILL
        alpha = 200
    }

    private var knobX = 0f
    private var knobY = 0f
    private var isDragging = false
    private var pointerId = -1

    // Specific mapping info
    private val axisX = if (node.type == ControlType.JOYSTICK_LEFT) GameActivity.GP_AXIS_LEFT_X else GameActivity.GP_AXIS_RIGHT_X
    private val axisY = if (node.type == ControlType.JOYSTICK_LEFT) GameActivity.GP_AXIS_LEFT_Y else GameActivity.GP_AXIS_RIGHT_Y

    private var tapStartTime = 0L
    private var lastTapTime = 0L
    private var isHoldingAttack = false
    
    // Holding previous aim direction briefly on release
    private var releaseAimTask: Runnable? = null
    private var lastReportedX = 0f
    private var lastReportedY = 0f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        resetKnob()
    }

    private fun resetKnob() {
        knobX = width / 2f
        knobY = height / 2f
        if (node.type == ControlType.JOYSTICK_RIGHT) {
            // Keep the last reported direction for 200ms, then zero it out
            releaseAimTask?.let { removeCallbacks(it) }
            val task = Runnable { reportAxis(0f, 0f) }
            releaseAimTask = task
            postDelayed(task, 200)
        } else {
            reportAxis(0f, 0f)
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val radius = min(width, height) / 2f
        val centerX = width / 2f
        val centerY = height / 2f
        
        canvas.drawCircle(centerX, centerY, radius, bgPaint)
        canvas.drawCircle(knobX, knobY, radius * 0.4f, knobPaint)
        
        super.onDraw(canvas)
    }

    override fun handleGameTouch(event: MotionEvent): Boolean {
        val action = event.actionMasked
        val pointerIndex = event.actionIndex

        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (!isDragging) {
                    val x = event.getX(pointerIndex)
                    val y = event.getY(pointerIndex)
                    val radius = min(width, height) / 2f
                    val dx = x - width / 2f
                    val dy = y - height / 2f
                    
                    if (sqrt((dx * dx + dy * dy).toDouble()) <= radius) {
                        isDragging = true
                        pointerId = event.getPointerId(pointerIndex)
                        
                        releaseAimTask?.let { removeCallbacks(it) }
                        
                        if (node.type == ControlType.JOYSTICK_RIGHT) {
                            val now = System.currentTimeMillis()
                            if (now - lastTapTime < 250) {
                                isHoldingAttack = true
                                NativeBridge.onGamepadAxis(TouchControlManager.AXIS_RTRIGGER, 1f)
                            }
                            tapStartTime = now
                        }
                        
                        updateKnob(x, y)
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragging) {
                    val index = event.findPointerIndex(pointerId)
                    if (index >= 0) {
                        updateKnob(event.getX(index), event.getY(index))
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragging && event.getPointerId(pointerIndex) == pointerId) {
                    isDragging = false
                    pointerId = -1
                    resetKnob()
                    
                    if (node.type == ControlType.JOYSTICK_RIGHT) {
                        if (isHoldingAttack) {
                            isHoldingAttack = false
                            NativeBridge.onGamepadAxis(TouchControlManager.AXIS_RTRIGGER, -1f)
                        } else if ((System.currentTimeMillis() - tapStartTime) < 200) {
                            // Simulate right trigger tap for Primary Attack
                            NativeBridge.onGamepadAxis(TouchControlManager.AXIS_RTRIGGER, 1f)
                            postDelayed({ NativeBridge.onGamepadAxis(TouchControlManager.AXIS_RTRIGGER, -1f) }, 50)
                            lastTapTime = System.currentTimeMillis()
                        } else {
                            lastTapTime = 0L // Was a drag, don't trigger double tap
                        }
                    }
                }
            }
        }
        return isDragging
    }

    private fun updateKnob(x: Float, y: Float) {
        val centerX = width / 2f
        val centerY = height / 2f
        val radius = min(width, height) / 2f
        val maxDist = radius * 0.6f

        val dx = x - centerX
        val dy = y - centerY
        val dist = sqrt((dx * dx + dy * dy).toDouble()).toFloat()

        if (dist <= maxDist) {
            knobX = x
            knobY = y
        } else {
            val angle = atan2(dy.toDouble(), dx.toDouble())
            knobX = centerX + (maxDist * cos(angle)).toFloat()
            knobY = centerY + (maxDist * sin(angle)).toFloat()
        }

        val normX = (knobX - centerX) / maxDist
        val normY = (knobY - centerY) / maxDist
        
        // If holding attack, report the tap direction even before the finger moves far
        reportAxis(normX, normY)
        invalidate()
    }

    private fun reportAxis(x: Float, y: Float) {
        lastReportedX = x
        lastReportedY = y
        NativeBridge.onGamepadAxis(axisX, x)
        NativeBridge.onGamepadAxis(axisY, y)
    }

}

class TouchButtonView(context: Context, node: ControlNode) : BaseTouchControl(context, node) {
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.DKGRAY
        style = Paint.Style.FILL
        alpha = 150
    }
    
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 40f
    }

    private var isPressedState = false
    private var isToggledOn = false

    private val buttonRect = RectF()

    override fun onDraw(canvas: Canvas) {
        val centerX = width / 2f
        val centerY = height / 2f
        val size = min(width, height).toFloat()
        
        buttonRect.set(
            centerX - size / 2f,
            centerY - size / 2f,
            centerX + size / 2f,
            centerY + size / 2f
        )
        
        bgPaint.color = if (isPressedState || isToggledOn) Color.LTGRAY else Color.DKGRAY
        
        // Use a rounded square instead of a circle
        val cornerRadius = size * 0.25f
        canvas.drawRoundRect(buttonRect, cornerRadius, cornerRadius, bgPaint)
        
        // Draw label
        if (node.label.isNotEmpty()) {
            val metrics = textPaint.fontMetrics
            val baseline = centerY - (metrics.ascent + metrics.descent) / 2
            canvas.drawText(node.label, centerX, baseline, textPaint)
        }
        
        super.onDraw(canvas)
    }

    override fun handleGameTouch(event: MotionEvent): Boolean {
        val action = event.actionMasked
        
        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                isPressedState = true
                if (node.isToggle) {
                    isToggledOn = !isToggledOn
                    reportInput(isToggledOn)
                } else {
                    reportInput(true)
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                isPressedState = false
                if (!node.isToggle) {
                    reportInput(false)
                }
                invalidate()
                return true
            }
        }
        return false
    }

    private fun reportInput(pressed: Boolean) {
        if (node.isAxisTrigger) {
            val v = if (pressed) 1f else -1f
            NativeBridge.onGamepadAxis(node.buttonCode, v)
        } else {
            NativeBridge.onGamepadButton(node.buttonCode, pressed)
        }
    }
}
