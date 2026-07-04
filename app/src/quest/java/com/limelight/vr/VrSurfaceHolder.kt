package com.limelight.vr

import android.graphics.Canvas
import android.graphics.Rect
import android.view.Surface
import android.view.SurfaceHolder

/**
 * Adapts a raw [Surface] (handed to us by the Meta Spatial SDK's
 * VideoSurfacePanelRegistration surfaceConsumer) to the [SurfaceHolder] interface
 * that [com.limelight.binding.video.MediaCodecDecoderRenderer.setRenderTarget]
 * expects.
 *
 * The decoder only ever calls [getSurface] on its render target (it configures the
 * MediaCodec with `renderTarget.getSurface()`), so that is the only method that must
 * be fully functional. The rest of the SurfaceHolder contract is implemented with
 * safe defaults: the callback registration methods are honored (harmlessly unused),
 * the drawing/locking methods throw because a hardware video Surface must never be
 * locked for Canvas drawing, and the sizing setters are recorded as no-ops.
 *
 * @param surface the panel-backed Surface to render decoded video into
 * @param videoWidth stream width in pixels, used to report [getSurfaceFrame]
 * @param videoHeight stream height in pixels, used to report [getSurfaceFrame]
 */
class VrSurfaceHolder(
    private val surface: Surface,
    private val videoWidth: Int,
    private val videoHeight: Int
) : SurfaceHolder {

    private val callbacks = ArrayList<SurfaceHolder.Callback>()
    private val surfaceFrame = Rect(0, 0, videoWidth, videoHeight)

    override fun getSurface(): Surface = surface

    override fun getSurfaceFrame(): Rect = surfaceFrame

    override fun addCallback(callback: SurfaceHolder.Callback?) {
        if (callback != null && !callbacks.contains(callback)) {
            callbacks.add(callback)
        }
    }

    override fun removeCallback(callback: SurfaceHolder.Callback?) {
        callbacks.remove(callback)
    }

    override fun isCreating(): Boolean = false

    // The panel Surface is a fixed-size hardware buffer owned by the Spatial SDK.
    // These sizing/format setters are meaningful only for View-backed SurfaceHolders,
    // so they are intentional no-ops here.
    override fun setType(type: Int) { /* deprecated / ignored */ }

    override fun setFixedSize(width: Int, height: Int) { /* fixed by the panel */ }

    override fun setSizeFromLayout() { /* no owning View to lay out against */ }

    override fun setFormat(format: Int) { /* format is chosen by the panel */ }

    override fun setKeepScreenOn(screenOn: Boolean) {
        // The VR compositor keeps the display on; nothing to do.
    }

    // A hardware video output Surface cannot be locked for Canvas drawing. The decoder
    // never calls these, so failing loudly is the correct behavior if something does.
    override fun lockCanvas(): Canvas =
        throw UnsupportedOperationException("VR video Surface cannot be locked for drawing")

    override fun lockCanvas(dirty: Rect?): Canvas =
        throw UnsupportedOperationException("VR video Surface cannot be locked for drawing")

    override fun lockHardwareCanvas(): Canvas =
        throw UnsupportedOperationException("VR video Surface cannot be locked for drawing")

    override fun unlockCanvasAndPost(canvas: Canvas?) {
        throw UnsupportedOperationException("VR video Surface cannot be locked for drawing")
    }
}
