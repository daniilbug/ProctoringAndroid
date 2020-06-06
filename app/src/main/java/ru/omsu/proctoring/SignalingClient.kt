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
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BroadcastChannel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ConflatedBroadcastChannel
import kotlinx.coroutines.flow.*
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription

@InternalCoroutinesApi
@FlowPreview
@ExperimentalCoroutinesApi
@KtorExperimentalAPI
class SignallingClient(
    private val serverAddress: String
) : CoroutineScope {

    enum class MessageAction {
        ICE_CANDIDATE,
        JOIN,
        CREATE_ANSWER,
        CREATE_OFFER,
        SESSION_DESCRIPTION,
        REMOVE,
        EXIT
    }

    data class Message(
        val action: MessageAction,
        val from: String? = null,
        val to: String? = null,
        val text: String = ""
    )

    private var listener: SignallingClientListener? = null
    private val job = Job()
    private val gson = Gson()
    override val coroutineContext = Dispatchers.IO + job

    private val client = HttpClient(CIO) {
        install(WebSockets)
        install(JsonFeature) {
            serializer = GsonSerializer()
        }
    }

    private val sendChannel = BroadcastChannel<String>(128)

    init {
        connect()
    }

    fun setMessageListener(listener: SignallingClientListener) {
        this.listener = listener
    }

    fun sendExit(local: String) = runBlocking {
        sendChannel.send(gson.toJson(Message(MessageAction.EXIT, local, null)))
    }

    fun sendDescription(toId: String, local: String, sessionDescription: SessionDescription) =
        runBlocking {
            val text = gson.toJson(sessionDescription)
            val message = Message(MessageAction.SESSION_DESCRIPTION, local, toId, text)
            sendChannel.send(gson.toJson(message))
        }

    fun sendCandidate(toId: String, local: String, iceCandidate: IceCandidate) = runBlocking {
        val messageText = gson.toJson(iceCandidate)
        val message = Message(MessageAction.ICE_CANDIDATE, local, toId, messageText)
        sendChannel.send(gson.toJson(message))
    }

    fun sendJoin(local: String) = runBlocking {
        sendChannel.send(gson.toJson(Message(MessageAction.JOIN, local, null)))
    }

    fun destroy() {
        client.close()
        job.complete()
    }

    private fun connect() = launch {
        client.ws(host = serverAddress, port = 8080, path = "/connect") {
            listener?.onConnectionEstablished()
            launch {
                sendChannel.asFlow().collect { sent ->
                    outgoing.send(Frame.Text(sent))
                    Log.v(this@SignallingClient.javaClass.simpleName, "Sending: $sent")
                }
            }
            incoming.consumeAsFlow().collect { frame ->
                if (frame is Frame.Text) {
                    val data = frame.readText()
                    Log.v(this@SignallingClient.javaClass.simpleName, "Received: $data")
                    val message = gson.fromJson(data, Message::class.java) ?: return@collect
                    withContext(Dispatchers.Main) {
                        processMessage(message)
                    }
                }
            }
        }
    }

    private fun processMessage(message: Message) {
        when (message.action) {
            MessageAction.CREATE_ANSWER -> onCreateAnswer(message)
            MessageAction.SESSION_DESCRIPTION -> onSessionDescription(message)
            MessageAction.REMOVE -> onRemove(message)
            MessageAction.CREATE_OFFER -> onCreateOffer(message)
            MessageAction.ICE_CANDIDATE -> onIceCandidate(message)
            else -> error("Unknown message action for getting as a client")
        }
    }

    private fun onIceCandidate(message: Message) {
        val from = message.from ?: return
        val to = message.to ?: return
        val candidate = createCandidate(gson.fromJson(message.text, JsonObject::class.java))
        listener?.onIceCandidateReceived(from, getLocalFromId(to), candidate)
    }

    private fun onCreateOffer(message: Message) {
        val from = message.from ?: return
        val to = message.to ?: return
        listener?.onCreateOfferRequest(from, getLocalFromId(to))
    }

    private fun onRemove(message: Message) {
        val from = message.from ?: return
        val to = message.to ?: return
        listener?.onRemove(from, getLocalFromId(to))
    }

    private fun onSessionDescription(message: Message) {
        val from = message.from ?: return
        val to = message.to ?: return
        val description = createSession(gson.fromJson(message.text, JsonObject::class.java))
        listener?.onSetRemoteSession(from, getLocalFromId(to), description)
    }

    private fun onCreateAnswer(message: Message) {
        val from = message.from ?: return
        val to = message.to ?: return
        val description = createSession(gson.fromJson(message.text, JsonObject::class.java))
        listener?.onCreateAnswerRequest(from, getLocalFromId(to), description)
    }

    private fun createCandidate(jsonObject: JsonObject): IceCandidate {
        val sdp = jsonObject.get("sdp")
        val sdpMid = jsonObject.get("sdpMid")
        val sdpMLineIndex = jsonObject.get("sdpMLineIndex")
        return IceCandidate(sdpMid.asString, sdpMLineIndex.asInt, sdp.asString)
    }

    private fun createSession(json: JsonObject): SessionDescription {
        val type = when (json["type"].asString) {
            "offer" -> SessionDescription.Type.OFFER
            "answer" -> SessionDescription.Type.ANSWER
            else -> error("Something went wrong with session type detection")
        }
        return SessionDescription(type, json["description"].asString)
    }

    private fun getLocalFromId(id: String): String {
        return if (":" in id) id.split(":")[1] else id
    }
}