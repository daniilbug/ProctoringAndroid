package ru.omsu.proctoring

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import io.ktor.util.KtorExperimentalAPI
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.webrtc.IceCandidate
import org.webrtc.MediaStream
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.lang.IllegalStateException

@ExperimentalCoroutinesApi
@KtorExperimentalAPI
class MainActivity : AppCompatActivity(), SignallingClientListener {

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
        webRtcClient.startCapturing(localCameraSurface)
        localCameraSurface.setOnClickListener {
            webRtcClient.call(sdpObserver)
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
}