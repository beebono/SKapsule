package com.skarm.launcher.touch

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

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

    init {
        // Build editor panel
        editorPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#80000000"))
            setPadding(32, 32, 32, 32)
            visibility = View.GONE
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER)
            
            // Global Settings
            val globalTitle = TextView(context).apply { text = "Global Settings"; setTextColor(Color.WHITE) }
            addView(globalTitle)

            val enableSwitch = Switch(context).apply {
                text = "Enable Controls"
                setTextColor(Color.WHITE)
                isChecked = layoutData.controlsEnabled
                setOnCheckedChangeListener { _, isChecked ->
                    layoutData.controlsEnabled = isChecked
                    updateVisibility()
                }
            }
            addView(enableSwitch)

            val opacityLabel = TextView(context).apply { text = "Opacity"; setTextColor(Color.WHITE) }
            addView(opacityLabel)
            val opacitySlider = SeekBar(context).apply {
                max = 100
                progress = (layoutData.globalOpacity * 100).toInt()
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        layoutData.globalOpacity = progress / 100f
                        updateOpacity()
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                })
            }
            addView(opacitySlider)

            // Separator
            addView(View(context).apply {
                setBackgroundColor(Color.LTGRAY)
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

            val scaleLabel = TextView(context).apply { 
                text = "Scale"
                setTextColor(Color.WHITE)
                tag = "scaleLabel"
                visibility = View.GONE
            }
            addView(scaleLabel)
            
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
        
        updateVisibility()
        updateOpacity()
    }

    private fun updateVisibility() {
        val enabled = layoutData.controlsEnabled
        for (view in controlViews) {
            if (inEditMode) {
                view.visibility = View.VISIBLE
                view.alpha = if (view.node.visible) layoutData.globalOpacity else 0.3f
            } else {
                view.visibility = if (enabled && view.node.visible) View.VISIBLE else View.GONE
                view.alpha = layoutData.globalOpacity
            }
        }
    }

    private fun updateOpacity() {
        val opacity = layoutData.globalOpacity
        for (view in controlViews) {
            if (inEditMode && !view.node.visible) {
                view.alpha = 0.3f
            } else {
                view.alpha = opacity
            }
        }
        
        // Notify Activity if there's a listener to update static buttons (Keyboard, Gear)
        opacityChangeListener?.invoke(opacity)
    }

    var opacityChangeListener: ((Float) -> Unit)? = null

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        
        val w = right - left
        val h = bottom - top

        // Base sizes
        val baseJoySize = minOf(w, h) * 0.3f
        val baseBtnSize = minOf(w, h) * 0.15f

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
        
        updateVisibility()
        updateEditorPanel()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!inEditMode) {
            // In play mode, let the overlay dispatch touches down to the controls,
            // or pass through to the game surface if no control was hit.
            var handled = false
            for (i in controlViews.indices.reversed()) {
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
                for (i in controlViews.indices.reversed()) {
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
        val nodeTitle = editorPanel.findViewWithTag<TextView>("nodeTitle")
        val visibleSwitch = editorPanel.findViewWithTag<Switch>("visibleSwitch")
        val scaleLabel = editorPanel.findViewWithTag<TextView>("scaleLabel")
        val scaleSlider = editorPanel.findViewWithTag<SeekBar>("scaleSlider")

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
}
