package com.limelight.vr

import com.meta.spatial.core.Entity
import com.meta.spatial.core.Query
import com.meta.spatial.core.SystemBase
import com.meta.spatial.core.Vector3
import com.meta.spatial.toolkit.Controller
import com.meta.spatial.toolkit.Scale

/**
 * Resizes the video panel in response to controller input, once per frame.
 *
 * The panel is moved with the grip (via its Grabbable component) and touched with the
 * trigger (via [VrPanelInput]); resize is bound to the face buttons so it never
 * conflicts with either:
 *   - Hold **A** (right controller) to grow the panel.
 *   - Hold **X** (left controller) to shrink it.
 *
 * Scale is uniform and clamped to a sane range so the panel can't be lost or inverted.
 *
 * Face buttons are used rather than the thumbstick because the [Controller] component's
 * button bit-field is a stable, documented API across Spatial SDK versions, whereas
 * analog thumbstick axis access differs between versions. Thumbstick resize can be
 * layered on later in [readResizeInput] without touching the rest of the system.
 */
class VrResizeSystem(private val panel: Entity) : SystemBase() {

    private var lastTimeMs: Long = 0

    private companion object {
        const val MIN_SCALE = 0.35f
        const val MAX_SCALE = 4.0f
        // Scale change per second while a resize button is held.
        const val SCALE_RATE_PER_SEC = 0.9f

        // Right-hand A and left-hand X, per Spatial SDK ButtonBits. Not `const`
        // because ButtonBits values are not guaranteed to be compile-time constants.
        val GROW_BUTTON = com.meta.spatial.runtime.ButtonBits.ButtonA
        val SHRINK_BUTTON = com.meta.spatial.runtime.ButtonBits.ButtonX
    }

    override fun execute() {
        // Compute frame delta time. getSceneModule()/time helpers vary, so we track
        // wall-clock deltas ourselves; the very first frame is skipped.
        val nowMs = System.currentTimeMillis()
        if (lastTimeMs == 0L) {
            lastTimeMs = nowMs
            return
        }
        val dt = (nowMs - lastTimeMs) / 1000f
        lastTimeMs = nowMs
        if (dt <= 0f) return

        val direction = readResizeInput()
        if (direction == 0f) return

        applyScaleDelta(direction * SCALE_RATE_PER_SEC * dt)
    }

    /**
     * @return +1 to grow, -1 to shrink, 0 for no resize this frame, based on the
     * aggregate button state of the local controllers.
     */
    private fun readResizeInput(): Float {
        var grow = false
        var shrink = false

        val controllers = Query.where { has(Controller.id) }.eval().filter { it.isLocal() }
        for (entity in controllers) {
            val controller = entity.tryGetComponent<Controller>() ?: continue
            if (!controller.isActive) continue
            if ((controller.buttonState and GROW_BUTTON) != 0) grow = true
            if ((controller.buttonState and SHRINK_BUTTON) != 0) shrink = true
        }

        return when {
            grow && !shrink -> 1f
            shrink && !grow -> -1f
            else -> 0f
        }
    }

    /** Apply a uniform scale delta to the panel, clamped to [MIN_SCALE, MAX_SCALE]. */
    private fun applyScaleDelta(delta: Float) {
        val scaleComponent = panel.tryGetComponent<Scale>()
        val current = scaleComponent?.scale?.x ?: 1f
        val next = (current + delta).coerceIn(MIN_SCALE, MAX_SCALE)
        if (next == current) return
        panel.setComponent(Scale(Vector3(next, next, next)))
    }
}
