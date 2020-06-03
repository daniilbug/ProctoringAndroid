package ru.omsu.proctoring

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.features.json.GsonSerializer
import io.ktor.client.features.json.JsonFeature
import io.ktor.client.features.websocket.WebSockets
import io.ktor.client.features.websocket.ws
import io.ktor.http.cio.websocket.Frame
import io.ktor.http.cio.websocket.readText
import io.ktor.util.KtorExperimentalAPI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.ConflatedBroadcastChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import java.util.*

@ExperimentalCoroutinesApi
@KtorExperimentalAPI
class SignallingClient(
    private val serverAddress: String,
    private val listener: SignallingClientListener
) : CoroutineScope {

    private val job = Job()
    private val gson = Gson()
    override val coroutineContext = Dispatchers.IO + job

    fun sendDescription(sessionDescription: SessionDescription) = runBlocking {
        sendChannel.send(gson.toJson(sessionDescription))
    }

    fun sendCandidate(iceCandidate: IceCandidate) = runBlocking {
        sendChannel.send(gson.toJson(iceCandidate))
    }

    fun destroy() {
        client.close()
        job.complete()
    }

    private val client = HttpClient(CIO) {
        install(WebSockets)
        install(JsonFeature) {
            serializer = GsonSerializer()
        }
    }

    private val sendChannel = ConflatedBroadcastChannel<String>()

    init {
        connect()
    }

    private fun connect() = launch {
        client.ws(host = serverAddress, port = 8080, path = "/connect") {
            listener.onConnectionEstablished()
            val sendData = sendChannel.openSubscription()
            while (true) {
                sendData.poll()?.let {
                    Log.v(this@SignallingClient.javaClass.simpleName, "Sending: $it")
                    outgoing.send(Frame.Text(it))
                }
                incoming.poll()?.let { frame ->
                    if (frame is Frame.Text) {
                        val data = frame.readText()
                        Log.v(this@SignallingClient.javaClass.simpleName, "Received: $data")
                        val jsonObject = gson.fromJson(data, JsonObject::class.java)
                        withContext(Dispatchers.Main) {
                            if (jsonObject.has("candidate")) {
                                listener.onIceCandidateReceived(createIce(jsonObject))
                            } else if (jsonObject.has("type") && jsonObject["type"].asString.toUpperCase(Locale.ROOT) == "OFFER") {
                                listener.onOfferReceived(createSession(jsonObject))
                            } else if (jsonObject.has("type") && jsonObject["type"].asString.toUpperCase(Locale.ROOT) == "ANSWER") {
                                listener.onAnswerReceived(createSession(jsonObject))
                            }
                        }
                    }
                }
            }
        }
    }

    private fun createIce(jsonObject: JsonObject): IceCandidate {
        val sdp = jsonObject.get("candidate")
        val sdpMid = jsonObject.get("sdpMid")
        val sdpMLineIndex = jsonObject.get("sdpMLineIndex")
        return IceCandidate(sdpMid.asString, sdpMLineIndex.asInt, sdp.asString)
    }

    private fun createSession(json: JsonObject): SessionDescription {
        val type = when(json["type"].asString) {
            "offer" -> SessionDescription.Type.OFFER
            "answer" -> SessionDescription.Type.ANSWER
            else -> error("Something went wrong with session type detection")
        }
        return SessionDescription(type, json["description"].asString)
    }
}