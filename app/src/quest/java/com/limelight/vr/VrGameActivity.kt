package com.limelight.vr

import android.content.Context
import android.net.ConnectivityManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.widget.Toast
import com.limelight.Game
import com.limelight.LimeLog
import com.limelight.R
import com.limelight.binding.PlatformBinding
import com.limelight.binding.input.ControllerHandler
import com.limelight.binding.video.CrashListener
import com.limelight.binding.video.MediaCodecDecoderRenderer
import com.limelight.binding.video.MediaCodecHelper
import com.limelight.binding.video.PerfOverlayListener
import com.limelight.nvstream.NvConnection
import com.limelight.nvstream.NvConnectionListener
import com.limelight.nvstream.StreamConfiguration
import com.limelight.nvstream.http.ComputerDetails
import com.limelight.nvstream.http.NvApp
import com.limelight.nvstream.input.MouseButtonPacket
import com.limelight.nvstream.jni.MoonBridge
import com.limelight.preferences.GlPreferences
import com.limelight.preferences.PreferenceConfiguration
import com.limelight.ui.GameGestures
import com.limelight.utils.UiHelper
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.SpatialFeature
import com.meta.spatial.core.Vector3
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.Grabbable
import com.meta.spatial.toolkit.GrabbableType
import com.meta.spatial.toolkit.MediaPanelRenderOptions
import com.meta.spatial.toolkit.MediaPanelSettings
import com.meta.spatial.toolkit.PanelRegistration
import com.meta.spatial.toolkit.PixelDisplayOptions
import com.meta.spatial.toolkit.QuadShapeOptions
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.VideoSurfacePanelRegistration
import com.meta.spatial.toolkit.Visible
import com.meta.spatial.vr.VRFeature
import java.io.ByteArrayInputStream
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * Immersive VR host for a Moonlight streaming session on Meta Quest.
 *
 * This is the Quest-flavor counterpart to [com.limelight.Game]. Rather than showing
 * the stream on a flat [com.limelight.ui.StreamView] SurfaceView, it renders the
 * decoded video onto a flat quad panel floating in a pitch-black void. The panel can
 * be grabbed and moved with a controller, and resized with the thumbstick (see
 * [VrResizeSystem]). Pointing a controller at the panel and pulling the trigger is
 * forwarded to the host as an absolute mouse/touch position + click.
 *
 * IMPORTANT: the entire Moonlight streaming stack is reused unchanged. We build the
 * exact same objects [com.limelight.Game] does — [PreferenceConfiguration],
 * [MediaCodecDecoderRenderer], [StreamConfiguration], [NvConnection],
 * [ControllerHandler] — and simply hand the decoder the panel-backed [Surface]
 * (via [VrSurfaceHolder]) instead of a view's surface. The gamepad is forwarded to
 * the host identically to the phone build.
 *
 * The activity reads the same {@code Game.EXTRA_*} intent extras that
 * [com.limelight.Game] does; [VrLaunch] + ServerHelper route stream launches here on
 * the quest flavor.
 */
class VrGameActivity : AppSystemActivity(), NvConnectionListener, GameGestures {

    // ---- Streaming stack (mirrors Game.java fields) ----
    private lateinit var prefConfig: PreferenceConfiguration
    private lateinit var decoderRenderer: MediaCodecDecoderRenderer
    private lateinit var conn: NvConnection
    private lateinit var controllerHandler: ControllerHandler
    private lateinit var app: NvApp

    private var streamWidth = 1280
    private var streamHeight = 720
    private var willStreamHdr = false

    private var attemptedConnection = false
    private var connecting = false
    private var connected = false

    private val mainHandler = Handler(Looper.getMainLooper())

    // ---- VR scene state ----
    private var videoPanelEntity: Entity? = null

    /**
     * Trigger-as-left-click edge tracker. onInput() reports the current pressed state
     * every frame; we translate that to press/release edges for the host, mirroring
     * the click behavior of AbsoluteTouchContext.
     */
    private var triggerDown = false

    companion object {
        // Id for the video panel registration/entity. Defined in quest-only
        // res/values/ids.xml (R.id.moonlight_video_panel). The SAME id must be used
        // for both the registration and the created entity.
        private val VIDEO_PANEL_ID = R.id.moonlight_video_panel

        // Default physical size of the quad, in meters. Width is derived per-stream
        // from the aspect ratio; height is the anchor. VrResizeSystem scales the
        // entity Transform around these values.
        const val DEFAULT_PANEL_HEIGHT_METERS = 1.2f

        // Distance in front of the user where the panel first appears.
        private const val INITIAL_PANEL_DISTANCE_METERS = 2.0f
        private const val INITIAL_PANEL_HEIGHT_METERS = 1.3f
    }

    // ------------------------------------------------------------------
    // Spatial SDK lifecycle
    // ------------------------------------------------------------------

    override fun registerFeatures(): List<SpatialFeature> {
        val features = mutableListOf<SpatialFeature>(VRFeature(this))
        return features
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Read the same preferences the flat activity uses.
        prefConfig = PreferenceConfiguration.readPreferences(this)
        streamWidth = prefConfig.width
        streamHeight = prefConfig.height

        // Build the whole streaming stack up front (connection is started later, from
        // the panel's surfaceConsumer, once we have a Surface to decode into).
        setupStreamingStack()
    }

    /**
     * Replicates the connection setup performed by Game.onCreate(): decoder, stream
     * configuration, connection and controller handler. Intentionally kept faithful
     * to the phone build so behavior on the host is identical.
     */
    private fun setupStreamingStack() {
        val intent = intent
        val host = intent.getStringExtra(Game.EXTRA_HOST)
        val port = intent.getIntExtra(Game.EXTRA_PORT, com.limelight.nvstream.http.NvHTTP.DEFAULT_HTTP_PORT)
        val httpsPort = intent.getIntExtra(Game.EXTRA_HTTPS_PORT, 0)
        val appId = intent.getIntExtra(Game.EXTRA_APP_ID, StreamConfiguration.INVALID_APP_ID)
        val uniqueId = intent.getStringExtra(Game.EXTRA_UNIQUEID)
        val appName = intent.getStringExtra(Game.EXTRA_APP_NAME)
        val appSupportsHdr = intent.getBooleanExtra(Game.EXTRA_APP_HDR, false)
        val derCertData = intent.getByteArrayExtra(Game.EXTRA_SERVER_CERT)

        app = NvApp(appName ?: "app", appId, appSupportsHdr)

        var serverCert: X509Certificate? = null
        try {
            if (derCertData != null) {
                serverCert = CertificateFactory.getInstance("X.509")
                    .generateCertificate(ByteArrayInputStream(derCertData)) as X509Certificate
            }
        } catch (e: CertificateException) {
            e.printStackTrace()
        }

        if (appId == StreamConfiguration.INVALID_APP_ID) {
            finish()
            return
        }

        // Initialize the MediaCodec helper before creating the decoder.
        val glPrefs = GlPreferences.readPreferences(this)
        MediaCodecHelper.initialize(this, glPrefs.glRenderer)

        val connMgr = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        // HDR on Quest is out of scope for this first version; we stream SDR. This
        // avoids depending on the flat display's HDR capability query.
        willStreamHdr = false

        decoderRenderer = MediaCodecDecoderRenderer(
            this,
            prefConfig,
            CrashListener { e -> LimeLog.warning("Decoder crash: " + (e?.message ?: "")) },
            0,
            connMgr.isActiveNetworkMetered,
            willStreamHdr,
            glPrefs.glRenderer,
            // No perf overlay in VR yet, but the decoder calls this unconditionally
            // when the perf-overlay pref is enabled, so pass a no-op rather than null.
            PerfOverlayListener { /* no-op */ }
        )

        // Assemble the set of supported video formats exactly like Game.java.
        var supportedVideoFormats = MoonBridge.VIDEO_FORMAT_H264
        if (decoderRenderer.isHevcSupported) {
            supportedVideoFormats = supportedVideoFormats or MoonBridge.VIDEO_FORMAT_H265
        }
        if (decoderRenderer.isAv1Supported) {
            supportedVideoFormats = supportedVideoFormats or MoonBridge.VIDEO_FORMAT_AV1_MAIN8
        }

        // getAttachedControllerMask() returns a short; the builder wants an int.
        var gamepadMask: Int = ControllerHandler.getAttachedControllerMask(this).toInt()
        if (!prefConfig.multiController) {
            gamepadMask = 1
        }

        val config = StreamConfiguration.Builder()
            .setResolution(prefConfig.width, prefConfig.height)
            .setLaunchRefreshRate(prefConfig.fps)
            .setRefreshRate(prefConfig.fps)
            .setApp(app)
            .setBitrate(prefConfig.bitrate)
            .setEnableSops(prefConfig.enableSops)
            .enableLocalAudioPlayback(prefConfig.playHostAudio)
            .setMaxPacketSize(1392)
            .setRemoteConfiguration(StreamConfiguration.STREAM_CFG_AUTO)
            .setSupportedVideoFormats(supportedVideoFormats)
            .setAttachedGamepadMask(gamepadMask)
            .setClientRefreshRateX100((prefConfig.fps * 100))
            .setAudioConfiguration(prefConfig.audioConfiguration)
            .setColorSpace(decoderRenderer.preferredColorSpace)
            .setColorRange(decoderRenderer.preferredColorRange)
            .setPersistGamepadsAfterDisconnect(!prefConfig.multiController)
            .build()

        conn = NvConnection(
            applicationContext,
            ComputerDetails.AddressTuple(host, port),
            httpsPort, uniqueId, config,
            PlatformBinding.getCryptoProvider(this), serverCert
        )

        controllerHandler = ControllerHandler(this, conn, this, prefConfig)

        if (!decoderRenderer.isAvcSupported) {
            Toast.makeText(this, "This device doesn't support hardware accelerated H.264 playback.", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    // ------------------------------------------------------------------
    // Panel registration — the video quad
    // ------------------------------------------------------------------

    override fun registerPanels(): List<PanelRegistration> {
        // VideoSurfacePanelRegistration hands us a raw Surface and renders directly to
        // it, bypassing the Android View system. That Surface is exactly what the
        // Moonlight decoder needs, so we wrap it and start the connection here — the
        // VR analogue of Game.surfaceChanged().
        return listOf(
            VideoSurfacePanelRegistration(
                VIDEO_PANEL_ID,
                surfaceConsumer = { _, surface ->
                    onVideoSurfaceAvailable(surface)
                },
                settingsCreator = {
                    MediaPanelSettings(
                        // Physical size in meters; keep the video aspect ratio.
                        shape = QuadShapeOptions(
                            width = DEFAULT_PANEL_HEIGHT_METERS * aspectRatio(),
                            height = DEFAULT_PANEL_HEIGHT_METERS
                        ),
                        // Panel texture resolution == stream resolution for 1:1 pixels.
                        display = PixelDisplayOptions(width = streamWidth, height = streamHeight),
                        rendering = MediaPanelRenderOptions()
                    )
                }
            )
        )
    }

    private fun aspectRatio(): Float {
        if (streamHeight <= 0) return 16f / 9f
        return streamWidth.toFloat() / streamHeight.toFloat()
    }

    /**
     * Called when the panel Surface is ready (potentially on the Spatial SDK render
     * thread). Hands the Surface to the decoder and starts the connection, mirroring
     * Game.surfaceChanged(). We marshal to the main thread so the streaming stack is
     * driven from the same thread as on the phone build.
     */
    private fun onVideoSurfaceAvailable(surface: Surface) {
        runOnUiThread {
            if (attemptedConnection) {
                return@runOnUiThread
            }
            attemptedConnection = true
            connecting = true

            val holder = VrSurfaceHolder(surface, streamWidth, streamHeight)

            // Update GameManager state to indicate we're "loading" while connecting.
            UiHelper.notifyStreamConnecting(this)

            decoderRenderer.setRenderTarget(holder)
            conn.start(
                com.limelight.binding.audio.AndroidAudioRenderer(this, prefConfig.enableAudioFx),
                decoderRenderer, this
            )
        }
    }

    // ------------------------------------------------------------------
    // Scene setup — the pitch-black void + grabbable panel
    // ------------------------------------------------------------------

    override fun onSceneReady() {
        super.onSceneReady()

        // Pitch-black void: no skybox entity, no environment mesh, and zero lighting.
        // With nothing to light and no environment texture, the compositor renders a
        // black background (the video panel is emissive/unlit so it stays visible).
        scene.setLightingEnvironment(
            ambientColor = Vector3(0f, 0f, 0f),
            sunColor = Vector3(0f, 0f, 0f),
            sunDirection = Vector3(0f, -1f, 0f),
            environmentIntensity = 0f
        )
        try {
            // Explicitly clear any default environment-based lighting/skybox.
            scene.setViewOrigin(0f, 0f, 0f, 0f)
        } catch (t: Throwable) {
            LimeLog.warning("setViewOrigin unsupported: " + t.message)
        }

        // Create the grabbable video panel a couple of meters in front of the user.
        // Grabbable(true, FACE) gives grab-to-move for free; VrResizeSystem handles
        // resize (A/X buttons). Face-type keeps the panel oriented toward the user.
        val initialPose = Pose(
            Vector3(0f, INITIAL_PANEL_HEIGHT_METERS, -INITIAL_PANEL_DISTANCE_METERS),
            Quaternion(0f, 0f, 0f)
        )
        videoPanelEntity = Entity.createPanelEntity(
            VIDEO_PANEL_ID,
            Transform(initialPose),
            Grabbable(true, GrabbableType.FACE),
            Visible(true)
        )

        // Register the input listener that maps controller pointer + trigger to host
        // touch/click, and the resize system that reads the face buttons.
        VrPanelInput.attach(this, videoPanelEntity!!)
        systemManager.registerSystem(VrResizeSystem(videoPanelEntity!!))
    }

    // ------------------------------------------------------------------
    // Controller pointer -> host mouse/touch (called by VrPanelInput)
    // ------------------------------------------------------------------

    /**
     * Forward an absolute pointer position on the panel to the host. [u],[v] are
     * normalized to [0,1] with the origin at the top-left of the video, matching
     * Android view coordinates and AbsoluteTouchContext.updatePosition().
     */
    fun onPanelPointerMove(u: Float, v: Float) {
        if (!connected) return
        val x = (u.coerceIn(0f, 1f) * streamWidth).toInt().toShort()
        val y = (v.coerceIn(0f, 1f) * streamHeight).toInt().toShort()
        conn.sendMousePosition(x, y, streamWidth.toShort(), streamHeight.toShort())
    }

    /**
     * Forward trigger state as a left mouse button. Called every frame with the
     * current pressed state; we send press/release only on edges.
     */
    fun onPanelTrigger(pressed: Boolean) {
        if (!connected) return
        if (pressed && !triggerDown) {
            triggerDown = true
            conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT)
        } else if (!pressed && triggerDown) {
            triggerDown = false
            conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT)
        }
    }

    /** Secondary button (e.g. B/Y) -> right click, sent as a press+release. */
    fun onPanelSecondaryClick() {
        if (!connected) return
        conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_RIGHT)
        mainHandler.postDelayed({
            if (connected) conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT)
        }, 60)
    }

    // ------------------------------------------------------------------
    // Gamepad forwarding — identical semantics to Game.java
    // ------------------------------------------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (::controllerHandler.isInitialized && ControllerHandler.isGameControllerDevice(event.device)) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (controllerHandler.handleButtonDown(event)) return true
            } else if (event.action == KeyEvent.ACTION_UP) {
                if (controllerHandler.handleButtonUp(event)) return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (::controllerHandler.isInitialized && controllerHandler.handleMotionEvent(event)) {
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    // ------------------------------------------------------------------
    // NvConnectionListener — minimal faithful implementations
    // ------------------------------------------------------------------

    override fun stageStarting(stage: String?) {
        LimeLog.info("VR stage starting: $stage")
    }

    override fun stageComplete(stage: String?) {}

    override fun stageFailed(stage: String?, portFlags: Int, errorCode: Int) {
        LimeLog.severe("VR stage failed: $stage ($errorCode)")
        runOnUiThread {
            Toast.makeText(this, getString(R.string.conn_error_msg) + " " + stage + " (error " + errorCode + ")", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun connectionStarted() {
        runOnUiThread {
            connected = true
            connecting = false
            UiHelper.notifyStreamConnected(this)
        }
    }

    override fun connectionTerminated(errorCode: Int) {
        LimeLog.severe("VR connection terminated: $errorCode")
        runOnUiThread {
            stopConnection()
            if (errorCode != MoonBridge.ML_ERROR_GRACEFUL_TERMINATION) {
                Toast.makeText(this, getString(R.string.conn_terminated_msg), Toast.LENGTH_LONG).show()
            }
            finish()
        }
    }

    override fun connectionStatusUpdate(connectionStatus: Int) {}

    override fun displayMessage(message: String?) {
        runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    }

    override fun displayTransientMessage(message: String?) {
        if (!prefConfig.disableWarnings) {
            runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
        }
    }

    override fun rumble(controllerNumber: Short, lowFreqMotor: Short, highFreqMotor: Short) {
        controllerHandler.handleRumble(controllerNumber, lowFreqMotor, highFreqMotor)
    }

    override fun rumbleTriggers(controllerNumber: Short, leftTrigger: Short, rightTrigger: Short) {
        controllerHandler.handleRumbleTriggers(controllerNumber, leftTrigger, rightTrigger)
    }

    override fun setHdrMode(enabled: Boolean, hdrMetadata: ByteArray?) {
        decoderRenderer.setHdrMode(enabled, hdrMetadata)
    }

    override fun setMotionEventState(controllerNumber: Short, motionType: Byte, reportRateHz: Short) {
        controllerHandler.handleSetMotionEventState(controllerNumber, motionType, reportRateHz)
    }

    override fun setControllerLED(controllerNumber: Short, r: Byte, g: Byte, b: Byte) {
        controllerHandler.handleSetControllerLED(controllerNumber, r, g, b)
    }

    // ------------------------------------------------------------------
    // GameGestures
    // ------------------------------------------------------------------

    override fun toggleKeyboard() {
        // No on-screen keyboard in this first VR version.
    }

    // ------------------------------------------------------------------
    // Teardown
    // ------------------------------------------------------------------

    private fun stopConnection() {
        if (connecting || connected) {
            connecting = false
            connected = false
            controllerHandler.stop()
            UiHelper.notifyStreamEnded(this)
            Thread { conn.stop() }.start()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopConnection()
    }
}
