package ru.omsu.proctoring

interface WebRtcStreamingStateListener {
    fun onEndCameraStream()
    fun onEndScreenStream()
    fun onStartCameraStream()
    fun onStartScreenStream()
}