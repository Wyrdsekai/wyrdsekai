package org.wyrdsekai.daemon

import android.util.Log
import io.nats.client.Connection
import io.nats.client.Nats
import io.nats.client.Options
import java.time.Duration

/**
 * Kotlin wrapper around jnats for Android daemon use.
 * Same NATS subjects and wire format as daemon-common's DaemonNatsClient,
 * but uses Android-idiomatic patterns (Log instead of SLF4J).
 */
class NatsConnectionWrapper(
    private val serverUrl: String,
    private val nodeId: String,
) {
    companion object {
        private const val TAG = "NatsConnection"
    }

    private var connection: Connection? = null

    fun connect() {
        val options = Options.Builder()
            .server(serverUrl)
            .connectionName("wyrd-daemon-${nodeId.take(8)}")
            .maxReconnects(-1)
            .reconnectWait(Duration.ofSeconds(2))
            .build()

        connection = Nats.connect(options)
        Log.i(TAG, "Connected to NATS at $serverUrl")
    }

    fun publish(subject: String, data: ByteArray) {
        connection?.publish(subject, data)
    }

    fun publishString(subject: String, json: String) {
        publish(subject, json.toByteArray(Charsets.UTF_8))
    }

    fun subscribe(subject: String, handler: (String) -> Unit) {
        val dispatcher = connection?.createDispatcher { msg ->
            try {
                handler(String(msg.data, Charsets.UTF_8))
            } catch (e: Exception) {
                Log.e(TAG, "Error processing message on $subject: ${e.message}")
            }
        }
        dispatcher?.subscribe(subject)
    }

    fun subscribeRequestReply(subject: String, handler: (ByteArray, (ByteArray) -> Unit) -> Unit) {
        val dispatcher = connection?.createDispatcher { msg ->
            try {
                handler(msg.data) { reply ->
                    connection?.publish(msg.replyTo, reply)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling request on $subject: ${e.message}")
            }
        }
        dispatcher?.subscribe(subject)
    }

    fun disconnect() {
        connection?.close()
        connection = null
        Log.i(TAG, "Disconnected from NATS")
    }

    fun isConnected(): Boolean =
        connection?.status == Connection.Status.CONNECTED
}
