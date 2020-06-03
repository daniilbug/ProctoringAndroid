package ru.omsu.proctoring

import android.R.attr.start
import android.annotation.TargetApi
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import io.ktor.util.KtorExperimentalAPI
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.webrtc.IceCandidate
import org.webrtc.MediaStream
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription


@ExperimentalCoroutinesApi
@KtorExperimentalAPI
class MainActivity : AppCompatActivity(), SignallingClientListener {

    companion object {
        const val CAPTURE_PERMISSION_REQUEST_CODE = 1
    }

    private lateinit var sdpObserver: SdpObserver
    private lateinit var signaling: SignallingClient
    private lateinit var webRtcClient: WebRtcClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        sdpObserver = object : SdpEmptyObserver() {
            override fun onCreateSuccess(description: SessionDescription?) {
                if (description != null)
                    signaling.sendDescription(description)
            }
        }
        signaling = SignallingClient("192.168.1.72", this)
        webRtcClient = WebRtcClient(application, object : PeerConnectionEmptyObserver() {
            override fun onIceCandidate(candidate: IceCandidate?) {
                if (candidate != null) {
                    signaling.sendCandidate(candidate)
                    webRtcClient.addIceCandidate(candidate)
                }
            }

            override fun onAddStream(stream: MediaStream?) {
                super.onAddStream(stream)
                stream?.videoTracks?.get(0)?.addSink(remoteCameraSurface)
            }
        })
        webRtcClient.initSurface(localCameraSurface)
        webRtcClient.initSurface(remoteCameraSurface)
        webRtcClient.startCapturingVideo(localCameraSurface)
        localCameraSurface.setOnClickListener {
            startScreenCapture()
        }
    }

    override fun onAnswerReceived(description: SessionDescription) {
        webRtcClient.onRemoteSessionDescription(description)
    }

    override fun onConnectionEstablished() {}

    override fun onIceCandidateReceived(iceCandidate: IceCandidate) {
        webRtcClient.addIceCandidate(iceCandidate)
    }

    override fun onOfferReceived(description: SessionDescription) {
        webRtcClient.onRemoteSessionDescription(description)
        webRtcClient.answer(sdpObserver)
    }

    override fun onDestroy() {
        signaling.destroy()
        super.onDestroy()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == CAPTURE_PERMISSION_REQUEST_CODE && data != null) {
            webRtcClient.startCapturingScreen(this, data)
            webRtcClient.call(sdpObserver)
        }
    }

    @TargetApi(21)
    private fun startScreenCapture() {
        val mMediaProjectionManager =
            application.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(
            mMediaProjectionManager.createScreenCaptureIntent(),
            CAPTURE_PERMISSION_REQUEST_CODE
        )
    }
}