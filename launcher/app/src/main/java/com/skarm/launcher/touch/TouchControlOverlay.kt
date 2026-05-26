package com.skarm.launcher.touch

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.skarm.launcher.NativeBridge

class TouchControlOverlay @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var layoutData: TouchLayoutData = TouchControlManager.loadLayout(context)
    private val controlViews = mutableListOf<BaseTouchControl>()
    private var inEditMode = false

    // Edit state
    private var selectedView: BaseTouchControl? = null
    private var dX = 0f
    private var dY = 0f

    // Editor UI Panel
    private val editorPanel: LinearLayout

    // Cached node settings views for fast updates
    private var cachedNodeTitle: TextView? = null
    private var cachedVisibleSwitch: Switch? = null
    private var cachedScaleLabel: TextView? = null
    private var cachedScaleSlider: SeekBar? = null

    // Reset Layout double-tap gate
    private var resetArmed = false
    private val resetDisarm = Runnable {
        resetArmed = false
        editorPanel.findViewWithTag<Button>("resetButton")?.text = RESET_LABEL
    }

    init {
        // Build editor panel
        editorPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(HudPalette.SURFACE)
            setPadding(32, 32, 32, 32)
            visibility = View.GONE
            // Bound the panel width so it doesn't stretch across the whole
            // screen (a MATCH_PARENT separator otherwise forces a WRAP_CONTENT
            // vertical LinearLayout to fill the full width).
            val panelWidth = (320 * resources.displayMetrics.density).toInt()
            layoutParams = LayoutParams(panelWidth, LayoutParams.WRAP_CONTENT, Gravity.CENTER)
            
            // Global Settings
            val globalTitle = TextView(context).apply { text = "Global Settings"; setTextColor(Color.WHITE) }
            addView(globalTitle)

            val enableSwitch = Switch(context).apply {
                text = "Enable Controls"
                setTextColor(Color.WHITE)
                tag = "enableSwitch"
                isChecked = layoutData.controlsEnabled
                setOnCheckedChangeListener { _, isChecked ->
                    layoutData.controlsEnabled = isChecked
                    applyControlAppearance()
                }
            }
            addView(enableSwitch)

            val opacityLabel = TextView(context).apply { text = "Opacity"; setTextColor(Color.WHITE) }
            addView(opacityLabel)
            val opacitySlider = SeekBar(context).apply {
                max = 100
                tag = "opacitySlider"
                progress = (layoutData.globalOpacity * 100).toInt()
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        layoutData.globalOpacity = progress / 100f
                        applyControlAppearance()
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                })
            }
            addView(opacitySlider)

            // Reset Layout (double-tap gated so a stray tap doesn't wipe work)
            val resetButton = Button(context).apply {
                text = RESET_LABEL
                tag = "resetButton"
                setOnClickListener { handleResetTap(this) }
            }
            addView(resetButton)

            // Separator
            addView(View(context).apply {
                setBackgroundColor(0x66FFFFFF.toInt())
                layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 2).apply {
                    setMargins(0, 16, 0, 16)
                }
            })

            // Node Settings (Hidden by default)
            val nodeTitle = TextView(context).apply { 
                text = "Node Settings"
                setTextColor(Color.WHITE)
                tag = "nodeTitle"
                visibility = View.GONE
            }
            addView(nodeTitle)
            cachedNodeTitle = nodeTitle

            val visibleSwitch = Switch(context).apply {
                text = "Visible"
                setTextColor(Color.WHITE)
                tag = "visibleSwitch"
                visibility = View.GONE
                setOnCheckedChangeListener { _, isChecked ->
                    selectedView?.let {
                        it.node.visible = isChecked
                        it.alpha = if (isChecked) layoutData.globalOpacity else 0.3f // keep visible in edit mode
                    }
                }
            }
            addView(visibleSwitch)
            cachedVisibleSwitch = visibleSwitch

            val scaleLabel = TextView(context).apply { 
                text = "Scale"
                setTextColor(Color.WHITE)
                tag = "scaleLabel"
                visibility = View.GONE
            }
            addView(scaleLabel)
            cachedScaleLabel = scaleLabel
            
            val scaleSlider = SeekBar(context).apply {
                max = 200 // 0.5x to 2.5x
                tag = "scaleSlider"
                visibility = View.GONE
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        selectedView?.let {
                            val scale = 0.5f + (progress / 100f)
                            it.node.scale = scale
                            requestLayout()
                        }
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                })
            }
            addView(scaleSlider)
            cachedScaleSlider = scaleSlider
        }

        buildControls()
        addView(editorPanel)
    }

    private fun buildControls() {
        controlViews.forEach { removeView(it) }
        controlViews.clear()

        for (node in layoutData.nodes) {
            val view = when (node.type) {
                ControlType.JOYSTICK_LEFT, ControlType.JOYSTICK_RIGHT -> TouchJoystickView(context, node)
                ControlType.BUTTON -> TouchButtonView(context, node)
            }
            addView(view)
            controlViews.add(view)
        }
        
        applyControlAppearance()
    }

    /**
     * Single source of truth for control visibility and alpha. Call this on any
     * state change (mode toggle, opacity change, enable toggle, rebuild) so the
     * displayed appearance never drifts out of sync.
     *
     * In edit mode visible controls are boosted to at least [EDIT_MODE_MIN_OPACITY]
     * so they're easy to see and drag; hidden controls show faintly. Exiting edit
     * mode restores the user's chosen global opacity (and hides hidden controls).
     */
    private fun applyControlAppearance() {
        val enabled = layoutData.controlsEnabled
        for (view in controlViews) {
            if (inEditMode) {
                view.visibility = View.VISIBLE
                view.alpha = if (view.node.visible) {
                    maxOf(layoutData.globalOpacity, EDIT_MODE_MIN_OPACITY)
                } else {
                    EDIT_HIDDEN_OPACITY
                }
            } else {
                view.visibility = if (enabled && view.node.visible) View.VISIBLE else View.GONE
                view.alpha = layoutData.globalOpacity
            }
        }

        // Keep SK's view of the virtual controller in sync: it must see the pad as
        // connected whenever the controls are enabled, or its glfwGetGamepadState
        // poll reports "no controller" and every touch input is dropped.
        NativeBridge.onVirtualGamepadConnected(enabled)

        // Notify Activity if there's a listener to update static buttons (Keyboard, Gear)
        opacityChangeListener?.invoke(layoutData.globalOpacity)
    }

    var opacityChangeListener: ((Float) -> Unit)? = null

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        
        val w = right - left
        val h = bottom - top

        // Base sizes
        val baseJoySize = w * 0.135f
        val baseBtnSize = w * 0.067f

        for (view in controlViews) {
            val node = view.node
            val size = (if (node.type == ControlType.BUTTON) baseBtnSize else baseJoySize) * node.scale
            
            val cx = w * node.xPercent
            val cy = h * node.yPercent
            
            val l = (cx - size / 2).toInt()
            val t = (cy - size / 2).toInt()
            val r = (cx + size / 2).toInt()
            val b = (cy + size / 2).toInt()
            
            view.layout(l, t, r, b)
        }
        
        // Ensure editor panel is brought to front and centered
        editorPanel.bringToFront()
    }

    fun toggleEditMode() {
        inEditMode = !inEditMode
        editorPanel.visibility = if (inEditMode) View.VISIBLE else View.GONE
        
        if (!inEditMode) {
            // Save layout when exiting edit mode
            TouchControlManager.saveLayout(context, layoutData)
            selectedView = null
        }
        
        for (view in controlViews) {
            view.inEditMode = inEditMode
            view.isSelectedNode = false
            view.invalidate()
        }
        
        applyControlAppearance()
        updateEditorPanel()
    }

    private fun handleResetTap(button: Button) {
        if (resetArmed) {
            removeCallbacks(resetDisarm)
            resetArmed = false
            button.text = RESET_LABEL
            resetLayout()
        } else {
            // First tap: arm and wait for a confirming second tap.
            resetArmed = true
            button.text = RESET_CONFIRM_LABEL
            removeCallbacks(resetDisarm)
            postDelayed(resetDisarm, RESET_CONFIRM_WINDOW_MS)
        }
    }

    private fun resetLayout() {
        layoutData = TouchControlManager.createDefaultLayout()
        selectView(null)
        buildControls()
        TouchControlManager.saveLayout(context, layoutData)

        // Refresh the global controls to reflect the restored defaults.
        editorPanel.findViewWithTag<Switch>("enableSwitch")?.isChecked = layoutData.controlsEnabled
        editorPanel.findViewWithTag<SeekBar>("opacitySlider")?.progress =
            (layoutData.globalOpacity * 100).toInt()

        editorPanel.bringToFront()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!inEditMode) {
            // In play mode, let the overlay dispatch touches down to the controls,
            // or pass through to the game surface if no control was hit.
            var handled = false
            for (i in controlViews.size - 1 downTo 0) {
                val view = controlViews[i]
                if (view.visibility == View.VISIBLE && isPointInsideView(event.x, event.y, view)) {
                    val viewEvent = MotionEvent.obtain(event)
                    viewEvent.offsetLocation(-view.left.toFloat(), -view.top.toFloat())
                    handled = view.dispatchTouchEvent(viewEvent) || handled
                    viewEvent.recycle()
                }
            }
            return handled
        }

        // --- Edit Mode Logic ---
        val action = event.actionMasked

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                // Find tapped view
                var tappedView: BaseTouchControl? = null
                for (i in controlViews.size - 1 downTo 0) {
                    val view = controlViews[i]
                    if (isPointInsideView(event.x, event.y, view)) {
                        tappedView = view
                        break
                    }
                }

                if (tappedView != null) {
                    selectView(tappedView)
                    dX = tappedView.x - event.x
                    dY = tappedView.y - event.y
                } else {
                    // Tap on empty space deselects
                    selectView(null)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                selectedView?.let { view ->
                    var newX = event.x + dX
                    var newY = event.y + dY
                    
                    // Constrain to screen
                    newX = newX.coerceIn(0f, width.toFloat() - view.width)
                    newY = newY.coerceIn(0f, height.toFloat() - view.height)
                    
                    view.x = newX
                    view.y = newY
                    
                    // Update node percent
                    view.node.xPercent = (newX + view.width / 2f) / width
                    view.node.yPercent = (newY + view.height / 2f) / height
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // Done dragging
                return true
            }
        }
        return false
    }
    
    private fun isPointInsideView(x: Float, y: Float, view: View): Boolean {
        return x >= view.left && x <= view.right && y >= view.top && y <= view.bottom
    }

    private fun selectView(view: BaseTouchControl?) {
        selectedView?.isSelectedNode = false
        selectedView?.invalidate()
        
        selectedView = view
        
        selectedView?.isSelectedNode = true
        selectedView?.invalidate()
        
        updateEditorPanel()
    }

    private fun updateEditorPanel() {
        val nodeTitle = cachedNodeTitle ?: return
        val visibleSwitch = cachedVisibleSwitch ?: return
        val scaleLabel = cachedScaleLabel ?: return
        val scaleSlider = cachedScaleSlider ?: return

        if (selectedView != null) {
            val node = selectedView!!.node
            nodeTitle.visibility = View.VISIBLE
            nodeTitle.text = "Settings: ${node.label.ifEmpty { node.id }}"
            
            visibleSwitch.visibility = View.VISIBLE
            visibleSwitch.isChecked = node.visible
            
            scaleLabel.visibility = View.VISIBLE
            scaleSlider.visibility = View.VISIBLE
            scaleSlider.progress = ((node.scale - 0.5f) * 100).toInt()
        } else {
            nodeTitle.visibility = View.GONE
            visibleSwitch.visibility = View.GONE
            scaleLabel.visibility = View.GONE
            scaleSlider.visibility = View.GONE
        }
    }

    companion object {
        private const val RESET_LABEL = "Reset Layout"
        private const val RESET_CONFIRM_LABEL = "Tap again to confirm"
        private const val RESET_CONFIRM_WINDOW_MS = 2500L

        // In edit mode, visible controls are shown at least this opaque so they're
        // easy to see and drag, even if the user's global opacity is very low.
        private const val EDIT_MODE_MIN_OPACITY = 0.85f
        // Hidden controls are shown faintly in edit mode so they can be re-enabled.
        private const val EDIT_HIDDEN_OPACITY = 0.3f
    }
}
