package ru.omsu.proctoring

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import io.ktor.util.KtorExperimentalAPI
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.webrtc.*


@KtorExperimentalAPI
@ExperimentalCoroutinesApi
class WebRtcClient(
    private val appContext: Context,
    private val signalingClient: SignallingClient,
    private val stateListener: WebRtcStreamingStateListener
) : SignallingClientListener {

    companion object {
        const val WIDTH = 320
        const val HEIGHT = 240
        const val FPS = 60
        const val SCREEN_ID = "local_screen_video"
        const val CAMERA_VIDEO_ID = "local_camera_video"
        const val CAMERA_STREAM_ID = "local_stream_camera"
        const val SCREEN_STREAM_ID = "local_stream_screen"

        const val CAMERA_LOCAL_ID = "camera"
        const val SCREEN_LOCAL_ID = "screen"
    }

    private val eglBase = EglBase.create()
    private val factory: PeerConnectionFactory
    private val localCameraStream: MediaStream
    private val localScreenStream: MediaStream
    private val connections = mutableMapOf<String, MutableMap<String, PeerConnection>>()

    private var screenCapturer: VideoCapturer? = null
    private var screenTrack: VideoTrack? = null
    private var cameraCapturer: VideoCapturer? = null
    private var cameraTrack: VideoTrack? = null

    init {
        initFactoryOptions()
        factory = createFactory()
        localCameraStream = factory.createLocalMediaStream(CAMERA_STREAM_ID)
        localScreenStream = factory.createLocalMediaStream(SCREEN_STREAM_ID)
    }

    override fun onConnectionEstablished() {}

    override fun onCreateAnswerRequest(fromId: String, localId: String, description: SessionDescription) {
        answer(fromId, localId, description)
    }

    override fun onCreateOfferRequest(toId: String, localId: String) {
        offer(toId, localId)
    }

    override fun onIceCandidateReceived(fromId: String, localId: String, iceCandidate: IceCandidate) {
        addIceCandidate(fromId, localId, iceCandidate)
    }

    override fun onRemove(id: String, localId: String) {
        connections[localId]?.get(id)?.close()
        connections[localId]?.remove(id)
    }

    override fun onSetRemoteSession(fromId: String, localId: String, description: SessionDescription) {
        onRemoteSessionDescription(fromId, localId, description)
    }

    fun startCameraStream(surface: SurfaceViewRenderer) {
        startCapturingVideo(surface)
        signalingClient.sendJoin(CAMERA_LOCAL_ID)
    }

    fun startScreenStream(context: Context, permissionData: Intent) {
        startCapturingScreen(context, permissionData)
        signalingClient.sendJoin(SCREEN_LOCAL_ID)
    }

    fun stopCameraStream() {
        connections[CAMERA_LOCAL_ID]?.values?.forEach { it.close() }
        signalingClient.sendExit(CAMERA_LOCAL_ID)
        cameraCapturer?.dispose()
        cameraTrack?.let { track ->
            localCameraStream.removeTrack(track)
            track.dispose()
        }
        stateListener.onEndCameraStream()
    }

    fun stopScreenSharing() {
        connections[SCREEN_LOCAL_ID]?.values?.forEach { it.close() }
        signalingClient.sendExit(SCREEN_LOCAL_ID)
        screenCapturer?.dispose()
        screenTrack?.let { track ->
            localScreenStream.removeTrack(track)
            track.dispose()
        }
        stateListener.onEndScreenStream()
    }

    fun initSurface(surface: SurfaceViewRenderer) = with(surface) {
        setMirror(false)
        setEnableHardwareScaler(true)
        init(eglBase.eglBaseContext, null)
    }

    private fun startCapturingVideo(surface: SurfaceViewRenderer) {
        val localVideoSource = factory.createVideoSource(false)
        val surfaceHelper =
            SurfaceTextureHelper.create(Thread.currentThread().name, eglBase.eglBaseContext)
        val capturer = getLocalCameraCapturer()
        capturer.initialize(surfaceHelper, surface.context, localVideoSource.capturerObserver)
        capturer.startCapture(WIDTH, HEIGHT, FPS)
        val localVideoTrack = factory.createVideoTrack(CAMERA_VIDEO_ID, localVideoSource)
        localVideoTrack.addSink(surface)
        cameraCapturer = capturer
        cameraTrack = localVideoTrack
        localCameraStream.addTrack(localVideoTrack)
    }

    private fun startCapturingScreen(context: Context, permissionData: Intent) {
        val localVideoSource = factory.createVideoSource(true)
        val surfaceHelper =
            SurfaceTextureHelper.create(Thread.currentThread().name, eglBase.eglBaseContext)
        val capturer = ScreenCapturerAndroid(permissionData, object : MediaProjection.Callback() {})
        capturer.initialize(surfaceHelper, context, localVideoSource.capturerObserver)
        capturer.startCapture(WIDTH, HEIGHT, FPS)
        val localVideoTrack = factory.createVideoTrack(SCREEN_ID, localVideoSource)
        screenCapturer = capturer
        screenTrack = localVideoTrack
        localScreenStream.addTrack(localVideoTrack)
    }

    private fun onRemoteSessionDescription(id: String, localId: String, description: SessionDescription) {
        connections[localId]?.get(id)?.setRemoteDescription(SdpEmptyObserver(), description)
    }

    private fun addIceCandidate(id: String, localId: String, iceCandidate: IceCandidate) {
        connections[localId]?.get(id)?.addIceCandidate(iceCandidate)
    }

    private fun createPeerConnection(id: String, localId: String, factory: PeerConnectionFactory): PeerConnection {
        val googleStunServerUrl = "stun:stun.l.google.com:19302"
        val iceServers =
            listOf(PeerConnection.IceServer.builder(googleStunServerUrl).createIceServer())
        val connection = factory.createPeerConnection(
            iceServers,
            object : PeerConnectionEmptyObserver() {
                override fun onIceCandidate(candidate: IceCandidate?) {
                    super.onIceCandidate(candidate)
                    if (candidate != null) {
                        signalingClient.sendCandidate(id, localId, candidate)
                        addIceCandidate(id, localId, candidate)
                    }
                }
            }
        ) ?: error("There was an error in peer connection creating")
        when(localId) {
            CAMERA_LOCAL_ID -> connection.addStream(localCameraStream)
            SCREEN_LOCAL_ID -> connection.addStream(localScreenStream)
        }
        return connection
    }

    private fun getLocalCameraCapturer() = with(Camera2Enumerator(appContext)) {
        val backCamera = deviceNames.find { name -> isBackFacing(name) }
        checkNotNull(backCamera) { "There must be the back camera to stream the video from it" }
        createCapturer(backCamera, null)
    }

    private fun initFactoryOptions() {
        val options = PeerConnectionFactory.InitializationOptions.builder(appContext)
            .setEnableInternalTracer(true)
            .setFieldTrials("WebRTC-H264HighProfile/Enabled/")
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)
    }

    private fun createFactory(): PeerConnectionFactory {
        return PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .setOptions(PeerConnectionFactory.Options().apply {
                disableNetworkMonitor = true
            }).createPeerConnectionFactory()
    }

    private fun offer(id: String, localId: String) {
        val connection = createPeerConnection(id, localId, factory)
        if (connections[localId] == null) {
            connections[localId] = mutableMapOf()
        }
        connections[localId]?.set(id, connection)
        connection.offer(id, localId)
        when(localId) {
            CAMERA_LOCAL_ID -> stateListener.onStartCameraStream()
            SCREEN_LOCAL_ID -> stateListener.onStartScreenStream()
        }
    }

    private fun answer(id: String, localId: String, sessionDescription: SessionDescription) {
        val connection = createPeerConnection(id, localId, factory)
        if (connections[localId] == null) {
            connections[localId] = mutableMapOf()
        }
        connections[localId]?.set(id, connection)
        onRemoteSessionDescription(id, localId, sessionDescription)
        connection.answer(id, localId)
        when(localId) {
            CAMERA_LOCAL_ID -> { stateListener.onStartCameraStream() }
            SCREEN_LOCAL_ID -> { stateListener.onStartScreenStream() }
        }
    }

    private fun PeerConnection.offer(toId: String, localId: String) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
            optional.add(MediaConstraints.KeyValuePair("DtlsSrtpKeyAgreement", "true"))
        }
        createOffer(object : SdpEmptyObserver() {
            override fun onCreateSuccess(description: SessionDescription?) {
                if (description != null) {
                    setLocalDescription(SdpEmptyObserver(), description)
                    signalingClient.sendDescription(toId, localId, description)
                }
            }
        }, constraints)
    }

    private fun PeerConnection.answer(toId: String, localId: String) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
            optional.add(MediaConstraints.KeyValuePair("DtlsSrtpKeyAgreement", "true"))
        }
        createAnswer(object : SdpEmptyObserver() {
            override fun onCreateSuccess(description: SessionDescription?) {
                if (description != null) {
                    setLocalDescription(SdpEmptyObserver(), description)
                    signalingClient.sendDescription(toId, localId, description)
                }
            }
        }, constraints)
    }
}