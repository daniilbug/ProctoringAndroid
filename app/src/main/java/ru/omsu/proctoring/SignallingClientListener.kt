package ru.omsu.proctoring

import org.webrtc.IceCandidate
import org.webrtc.SessionDescription

interface SignallingClientListener {
    fun onConnectionEstablished()
    fun onCreateAnswerRequest(fromId: String, localId: String, description: SessionDescription)
    fun onCreateOfferRequest(toId: String, localId: String)
    fun onIceCandidateReceived(fromId: String, localId: String, iceCandidate: IceCandidate)
    fun onSetRemoteSession(fromId: String, localId: String, description: SessionDescription)
    fun onRemove(id: String, localId: String)
}