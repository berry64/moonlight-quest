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
 * This class only forwards the raw world-space hit point + button state to the
 * activity. The hit-point -> normalized-panel-UV conversion lives in
 * [VrGameActivity.onPanelHit], where the panel's live Transform/Scale and physical
 * dimensions are known. (HitInfo in Spatial SDK 0.13.x exposes the hit `point` in
 * world space, not a UV.)
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
                    val triggerPressed = (clicked and TRIGGER_BITS) != 0
                    val secondaryEdge =
                        (changed and SECONDARY_BITS) != 0 && (clicked and SECONDARY_BITS) != 0

                    // Hand the world-space hit point + button state to the activity,
                    // which converts it to a normalized panel position and forwards it
                    // to the host input path. onInput fires while pointing at/
                    // interacting with the panel, so this keeps the host cursor under
                    // the laser.
                    activity.onPanelHit(hitInfo.point, triggerPressed, secondaryEdge)

                    // Returning false lets the SDK keep processing (grip-grab, pointer
                    // visuals, etc.); we only observe input.
                    return false
                }
            })
        }
    }
}
