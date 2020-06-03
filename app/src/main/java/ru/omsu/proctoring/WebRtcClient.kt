package ru.omsu.proctoring

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import org.webrtc.*


class WebRtcClient(
    private val appContext: Context,
    private val connectionObserver: PeerConnection.Observer
) {
    companion object {
        const val WIDTH = 320
        const val HEIGHT = 240
        const val FPS = 60
        const val SCREEN_ID = "local_screen_video"
        const val CAMERA_VIDEO_ID = "local_camera_video"
        const val STREAM_ID = "local_stream"
    }

    private val eglBase = EglBase.create()
    private val factory: PeerConnectionFactory
    private val connection: PeerConnection
    private  val localStream: MediaStream

    init {
        initFactoryOptions()
        factory = createFactory()
        connection = createPeerConnection(factory)
        localStream = factory.createLocalMediaStream(STREAM_ID)
        connection.addStream(localStream)
    }

    fun initSurface(surface: SurfaceViewRenderer) = with(surface) {
        setMirror(false)
        setEnableHardwareScaler(true)
        init(eglBase.eglBaseContext, null)
    }

    fun startCapturingVideo(surface: SurfaceViewRenderer) {
        val localVideoSource = factory.createVideoSource(false)
        val surfaceHelper =
            SurfaceTextureHelper.create(Thread.currentThread().name, eglBase.eglBaseContext)
        val capturer = getLocalCameraCapturer()
        capturer.initialize(surfaceHelper, surface.context, localVideoSource.capturerObserver)
        capturer.startCapture(WIDTH, HEIGHT, FPS)
        val localVideoTrack = factory.createVideoTrack(CAMERA_VIDEO_ID, localVideoSource)
        localVideoTrack.addSink(surface)
        localStream.addTrack(localVideoTrack)
    }

    fun startCapturingScreen(context: Context, permissionData: Intent) {
        val localVideoSource = factory.createVideoSource(true)
        val surfaceHelper =
            SurfaceTextureHelper.create(Thread.currentThread().name, eglBase.eglBaseContext)
        val capturer = ScreenCapturerAndroid(permissionData, object : MediaProjection.Callback() {})
        capturer.initialize(surfaceHelper, context, localVideoSource.capturerObserver)
        capturer.startCapture(WIDTH, HEIGHT, FPS)
        val localVideoTrack = factory.createVideoTrack(SCREEN_ID, localVideoSource)
        localStream.addTrack(localVideoTrack)
    }

    fun call(sdpObserver: SdpObserver) {
        connection.call(sdpObserver)
    }

    fun answer(sdpObserver: SdpObserver) {
        connection.answer(sdpObserver)
    }

    fun onRemoteSessionDescription(description: SessionDescription) {
        connection.setRemoteDescription(SdpEmptyObserver(), description)
    }

    fun addIceCandidate(iceCandidate: IceCandidate) {
        connection.addIceCandidate(iceCandidate)
    }

    private fun createPeerConnection(factory: PeerConnectionFactory): PeerConnection {
        val googleStunServerUrl = "stun:stun.l.google.com:19302"
        val iceServers =
            listOf(PeerConnection.IceServer.builder(googleStunServerUrl).createIceServer())
        return factory.createPeerConnection(
            iceServers,
            connectionObserver
        ) ?: error("There was an error in peer connection creating")
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

    private fun PeerConnection.call(sdpObserver: SdpObserver) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
            optional.add(MediaConstraints.KeyValuePair("DtlsSrtpKeyAgreement", "true"))
        }
        createOffer(object: SdpObserver by sdpObserver {
            override fun onCreateSuccess(description: SessionDescription?) {
                setLocalDescription(SdpEmptyObserver(), description)
                sdpObserver.onCreateSuccess(description)
            }
        }, constraints)
    }

    private fun PeerConnection.answer(sdpObserver: SdpObserver) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
            optional.add(MediaConstraints.KeyValuePair("DtlsSrtpKeyAgreement", "true"))
        }
        createAnswer(object: SdpObserver by sdpObserver {
            override fun onCreateSuccess(description: SessionDescription?) {
                setLocalDescription(SdpEmptyObserver(), description)
                sdpObserver.onCreateSuccess(description)
            }

            override fun onCreateFailure(message: String?) {
                throw IllegalStateException(message)
            }
        }, constraints)
    }
}