package com.limelight.vr

import com.limelight.LimeLog
import com.meta.spatial.core.Entity
import com.meta.spatial.runtime.ButtonBits
import com.meta.spatial.runtime.HitInfo
import com.meta.spatial.runtime.InputListener
import com.meta.spatial.runtime.SceneObject
import com.meta.spatial.toolkit.SceneObjectSystem

/**
 * Maps controller interaction with the video panel to Moonlight host input.
 *
 * The Quest Touch controllers act as a laser pointer: where the pointer hits the
 * panel becomes an absolute mouse position on the host, and the trigger becomes a
 * left click. This mirrors [com.limelight.binding.input.touch.AbsoluteTouchContext]
 * on the phone build, but sourced from the VR pointer instead of a touchscreen.
 *
 * Grabbing/moving the panel (grip) is handled by the panel's Grabbable component, and
 * resizing by [VrResizeSystem]; neither is handled here.
 *
 * NOTE (SDK seam): the exact way a [HitInfo] exposes the hit location in panel-local
 * UV space can vary between Spatial SDK versions. That conversion is isolated in
 * [hitInfoToUv] so it is the single place to adjust if the installed SDK differs.
 */
object VrPanelInput {

    // Buttons treated as a "touch/click" on the panel: either trigger.
    // Not `const` because ButtonBits values aren't guaranteed compile-time constants.
    private val TRIGGER_BITS = ButtonBits.ButtonTriggerR or ButtonBits.ButtonTriggerL

    // Buttons treated as a secondary (right) click: B or Y.
    private val SECONDARY_BITS = ButtonBits.ButtonB or ButtonBits.ButtonY

    /**
     * Attach an [InputListener] to the panel entity's [SceneObject]. Must be called
     * after the entity is created (e.g. from onSceneReady()).
     */
    fun attach(activity: VrGameActivity, panel: Entity) {
        val sceneObjectSystem = activity.systemManager.findSystem<SceneObjectSystem>()
        if (sceneObjectSystem == null) {
            LimeLog.warning("VrPanelInput: SceneObjectSystem not available")
            return
        }

        sceneObjectSystem.getSceneObject(panel)?.thenAccept { sceneObject ->
            sceneObject.addInputListener(object : InputListener {
                override fun onInput(
                    receiver: SceneObject,
                    hitInfo: HitInfo,
                    sourceOfInput: Entity,
                    changed: Int,
                    clicked: Int,
                    downTime: Long
                ): Boolean {
                    // 1) Move the host cursor to wherever the pointer is hitting the
                    //    panel. onInput fires while pointing at/interacting with the
                    //    panel, so this keeps the host cursor under the laser.
                    val uv = hitInfoToUv(hitInfo)
                    if (uv != null) {
                        activity.onPanelPointerMove(uv[0], uv[1])
                    }

                    // 2) Trigger -> left click (press on rising edge, release on
                    //    falling edge). We pass the current pressed state; the
                    //    activity converts it to press/release edges.
                    val triggerPressed = (clicked and TRIGGER_BITS) != 0
                    activity.onPanelTrigger(triggerPressed)

                    // 3) B/Y -> right click, but only on the rising edge.
                    if ((changed and SECONDARY_BITS) != 0 && (clicked and SECONDARY_BITS) != 0) {
                        activity.onPanelSecondaryClick()
                    }

                    // Returning false lets the SDK keep processing (e.g. so grip-grab
                    // and the pointer visuals still work); we only observe input.
                    return false
                }
            })
        }
    }

    /**
     * Convert a panel [HitInfo] to normalized panel coordinates in [0,1], origin
     * top-left, x to the right, y downward (matching Android view coordinates that
     * the Moonlight host-input path expects).
     *
     * SDK seam: this uses the hit info's UV if the SDK provides one. If a given SDK
     * version instead exposes only a world/local hit point, derive UV from the panel
     * shape here. Returns null if no usable hit location is available this frame.
     */
    private fun hitInfoToUv(hitInfo: HitInfo): FloatArray? {
        return try {
            // Spatial SDK HitInfo exposes the hit location on the panel. The uv is
            // reported with origin at the bottom-left in GL convention, so flip Y to
            // get the top-left origin the host expects.
            val u = hitInfo.uv.x
            val v = 1f - hitInfo.uv.y
            floatArrayOf(u, v)
        } catch (t: Throwable) {
            // If this SDK version doesn't expose .uv, this is the single place to map
            // hitInfo.point (world space) into panel-local UV using the panel's
            // Transform + QuadShapeOptions dimensions.
            LimeLog.warning("VrPanelInput: could not read hit UV: " + t.message)
            null
        }
    }
}
