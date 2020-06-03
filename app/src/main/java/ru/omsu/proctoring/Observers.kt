package ru.omsu.proctoring

import org.webrtc.*
import java.lang.IllegalStateException

open class PeerConnectionEmptyObserver: PeerConnection.Observer {
    override fun onAddStream(stream: MediaStream?) {}

    override fun onSignalingChange(signalingState: PeerConnection.SignalingState?) {}

    override fun onIceCandidatesRemoved(removed: Array<out IceCandidate>?) {}

    override fun onRemoveStream(removed: MediaStream?) {}

    override fun onRenegotiationNeeded() {}

    override fun onAddTrack(reviever: RtpReceiver?, streams: Array<out MediaStream>?) {}

    override fun onIceCandidate(candidate: IceCandidate?) {}

    override fun onDataChannel(channel: DataChannel?) {}

    override fun onIceConnectionReceivingChange(flag: Boolean) {}

    override fun onIceConnectionChange(iceConnectionState: PeerConnection.IceConnectionState?) {}

    override fun onIceGatheringChange(iceGatheringState: PeerConnection.IceGatheringState?) {}
}

open class SdpEmptyObserver: SdpObserver {
    override fun onCreateFailure(fail: String?) {
        throw IllegalStateException(fail)
    }

    override fun onCreateSuccess(description: SessionDescription?) {
    }

    override fun onSetFailure(fail: String?) {
    }

    override fun onSetSuccess() {
    }
}