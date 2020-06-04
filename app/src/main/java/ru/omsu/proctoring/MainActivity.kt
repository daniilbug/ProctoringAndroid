package ru.omsu.proctoring

import android.annotation.TargetApi
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import io.ktor.util.KtorExperimentalAPI
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlin.math.sign


@ExperimentalCoroutinesApi
@KtorExperimentalAPI
class MainActivity : AppCompatActivity(), WebRtcStreamingStateListener {

    companion object {
        const val CAPTURE_PERMISSION_REQUEST_CODE = 1
    }

    private lateinit var signaling: SignallingClient
    private lateinit var webRtcClient: WebRtcClient

    private var cameraStreaming = false
    private var screenStreaming = false
    private var sending = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        signaling = SignallingClient("192.168.1.72")
        webRtcClient = WebRtcClient(application, signaling, this)
        signaling.setMessageListener(webRtcClient)

        webRtcClient.initSurface(localCameraSurface)

        cameraStreamButton.setOnClickListener {
            if (sending) return@setOnClickListener
            sending = true
            if (cameraStreaming) {
                webRtcClient.stopCameraStream()
            } else {
                webRtcClient.startCameraStream(localCameraSurface)
            }
        }

        screenStreamButton.setOnClickListener {
            if (sending) return@setOnClickListener
            sending = true
            if (screenStreaming) {
                webRtcClient.stopScreenSharing()
            } else {
                startScreenCapture()
            }
        }
    }

    override fun onDestroy() {
        signaling.destroy()
        super.onDestroy()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == CAPTURE_PERMISSION_REQUEST_CODE && data != null) {
            webRtcClient.startScreenStream(this, data)
        }
    }

    override fun onEndCameraStream() {
        cameraStreamButton.setText(R.string.startCameraStream)
        cameraStreaming = false
        sending = false
    }

    override fun onEndScreenStream() {
        screenStreamButton.setText(R.string.startScreenSharing)
        screenStreaming = false
        sending = false
    }

    override fun onStartCameraStream() {
        cameraStreamButton.setText(R.string.stopCameraStream)
        cameraStreaming = true
        sending = false
    }

    override fun onStartScreenStream() {
        screenStreamButton.setText(R.string.stopScreenSharing)
        screenStreaming = true
        sending = false
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