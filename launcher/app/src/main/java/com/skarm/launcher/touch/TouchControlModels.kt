package com.skarm.launcher.touch

import android.content.Context
import android.content.SharedPreferences
import android.view.KeyEvent
import com.skarm.launcher.GameActivity
import org.json.JSONArray
import org.json.JSONObject

enum class ControlType {
    BUTTON,
    JOYSTICK_LEFT,
    JOYSTICK_RIGHT
}

data class ControlNode(
    val id: String,
    val type: ControlType,
    var xPercent: Float, // 0.0 to 1.0 (relative to screen width)
    var yPercent: Float, // 0.0 to 1.0 (relative to screen height)
    var scale: Float = 1.0f,
    var visible: Boolean = true,
    
    // For Buttons
    val buttonCode: Int = -1, // GameActivity.GP_BTN_* or axis for triggers
    val isAxisTrigger: Boolean = false, // True if buttonCode represents an axis (like LTrig)
    val isToggle: Boolean = false, // True for the Strafe button
    val label: String = ""
)

data class TouchLayoutData(
    var globalOpacity: Float = 0.5f,
    var controlsEnabled: Boolean = true,
    val nodes: MutableList<ControlNode> = mutableListOf()
)

object TouchControlManager {
    private const val PREFS_NAME = "touch_controls_prefs"
    private const val KEY_LAYOUT_DATA = "layout_data"
    
    // Axis codes for triggers, mapped to GameActivity constants
    const val AXIS_LTRIGGER = 4
    const val AXIS_RTRIGGER = 5

    // Button codes from GameActivity
    const val GP_BTN_A = 0
    const val GP_BTN_B = 1
    const val GP_BTN_X = 2
    const val GP_BTN_Y = 3
    const val GP_BTN_LEFT_BUMPER = 4
    const val GP_BTN_RIGHT_BUMPER = 5
    const val GP_BTN_BACK = 6
    const val GP_BTN_START = 7
    const val GP_BTN_GUIDE = 8
    const val GP_BTN_LEFT_THUMB = 9
    const val GP_BTN_RIGHT_THUMB = 10
    const val GP_BTN_DPAD_UP = 11
    const val GP_BTN_DPAD_RIGHT = 12
    const val GP_BTN_DPAD_DOWN = 13
    const val GP_BTN_DPAD_LEFT = 14

    fun loadLayout(context: Context): TouchLayoutData {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_LAYOUT_DATA, null)
        
        if (jsonStr.isNullOrEmpty()) {
            return createDefaultLayout()
        }
        
        return try {
            val json = JSONObject(jsonStr)
            val layout = TouchLayoutData(
                globalOpacity = json.optDouble("globalOpacity", 0.5).toFloat(),
                controlsEnabled = json.optBoolean("controlsEnabled", true)
            )
            
            val nodesArray = json.getJSONArray("nodes")
            for (i in 0 until nodesArray.length()) {
                val nodeObj = nodesArray.getJSONObject(i)
                val type = ControlType.valueOf(nodeObj.getString("type"))
                layout.nodes.add(ControlNode(
                    id = nodeObj.getString("id"),
                    type = type,
                    xPercent = nodeObj.getDouble("xPercent").toFloat(),
                    yPercent = nodeObj.getDouble("yPercent").toFloat(),
                    scale = nodeObj.optDouble("scale", 1.0).toFloat(),
                    visible = nodeObj.optBoolean("visible", true),
                    buttonCode = nodeObj.optInt("buttonCode", -1),
                    isAxisTrigger = nodeObj.optBoolean("isAxisTrigger", false),
                    isToggle = nodeObj.optBoolean("isToggle", false),
                    label = nodeObj.optString("label", "")
                ))
            }
            layout
        } catch (e: Exception) {
            e.printStackTrace()
            createDefaultLayout()
        }
    }

    fun saveLayout(context: Context, layout: TouchLayoutData) {
        val json = JSONObject().apply {
            put("globalOpacity", layout.globalOpacity.toDouble())
            put("controlsEnabled", layout.controlsEnabled)
            
            val nodesArray = JSONArray()
            for (node in layout.nodes) {
                val nodeObj = JSONObject().apply {
                    put("id", node.id)
                    put("type", node.type.name)
                    put("xPercent", node.xPercent.toDouble())
                    put("yPercent", node.yPercent.toDouble())
                    put("scale", node.scale.toDouble())
                    put("visible", node.visible)
                    put("buttonCode", node.buttonCode)
                    put("isAxisTrigger", node.isAxisTrigger)
                    put("isToggle", node.isToggle)
                    put("label", node.label)
                }
                nodesArray.put(nodeObj)
            }
            put("nodes", nodesArray)
        }
        
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAYOUT_DATA, json.toString())
            .apply()
    }

    private fun createDefaultLayout(): TouchLayoutData {
        val layout = TouchLayoutData()
        
        // Left Joystick (Move)
        layout.nodes.add(ControlNode("joy_move", ControlType.JOYSTICK_LEFT, 0.15f, 0.7f))
        
        // Right Joystick (Face) - Tap to Primary Attack handled in Joystick implementation
        layout.nodes.add(ControlNode("joy_face", ControlType.JOYSTICK_RIGHT, 0.85f, 0.7f))

        // Strafe (R3) - toggle button offset to the lower right of Movement Circle pad
        layout.nodes.add(ControlNode("btn_strafe", ControlType.BUTTON, 0.28f, 0.85f, buttonCode = GP_BTN_RIGHT_THUMB, isToggle = true, label = "Strafe"))

        // Defend (LTrig) - hold button offset to lower left of Facing Circle pad
        layout.nodes.add(ControlNode("btn_defend", ControlType.BUTTON, 0.72f, 0.85f, buttonCode = AXIS_LTRIGGER, isAxisTrigger = true, label = "Defend"))

        // Dodge (L3) - tap button offset above the Facing Circle pad
        layout.nodes.add(ControlNode("btn_dodge", ControlType.BUTTON, 0.78f, 0.45f, buttonCode = GP_BTN_LEFT_THUMB, label = "Dodge"))

        // ShieldBash (Y) - tap button offset above the Facing Circle pad
        layout.nodes.add(ControlNode("btn_shieldbash", ControlType.BUTTON, 0.88f, 0.45f, buttonCode = GP_BTN_Y, label = "Bash"))

        // Prev Weap (L1) - Up glyph arrow button offset to the right of the Facing Circle pad
        layout.nodes.add(ControlNode("btn_prevweap", ControlType.BUTTON, 0.95f, 0.55f, buttonCode = GP_BTN_LEFT_BUMPER, label = "↑"))

        // Next Weap (R1) - Down glyph arrow button offset to the right of the Facing Circle pad
        layout.nodes.add(ControlNode("btn_nextweap", ControlType.BUTTON, 0.95f, 0.85f, buttonCode = GP_BTN_RIGHT_BUMPER, label = "↓"))

        // Bottom centered buttons in a row from left to right by default
        val centerStartX = 0.35f
        val bottomY = 0.9f
        val gap = 0.08f

        layout.nodes.add(ControlNode("btn_ab1", ControlType.BUTTON, centerStartX + gap * 0, bottomY, buttonCode = GP_BTN_A, label = "A1"))
        layout.nodes.add(ControlNode("btn_ab2", ControlType.BUTTON, centerStartX + gap * 1, bottomY, buttonCode = GP_BTN_B, label = "A2"))
        layout.nodes.add(ControlNode("btn_ab3", ControlType.BUTTON, centerStartX + gap * 2, bottomY, buttonCode = GP_BTN_X, label = "A3"))
        
        layout.nodes.add(ControlNode("btn_item1", ControlType.BUTTON, centerStartX + gap * 3, bottomY, buttonCode = GP_BTN_DPAD_UP, label = "I1"))
        layout.nodes.add(ControlNode("btn_item2", ControlType.BUTTON, centerStartX + gap * 4, bottomY, buttonCode = GP_BTN_DPAD_RIGHT, label = "I2"))
        layout.nodes.add(ControlNode("btn_item3", ControlType.BUTTON, centerStartX + gap * 5, bottomY, buttonCode = GP_BTN_DPAD_DOWN, label = "I3"))
        layout.nodes.add(ControlNode("btn_item4", ControlType.BUTTON, centerStartX + gap * 6, bottomY, buttonCode = GP_BTN_DPAD_LEFT, label = "I4"))

        return layout
    }
}
