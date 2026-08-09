package dev.ruri.il2cppmanager.ipc

import android.os.Bundle
import android.os.Message
import android.os.Messenger

data class RequestEnvelope(
    val command: Int,
    val requestId: Long,
    val payload: Bundle,
    val replyTo: Messenger,
)

data class ResponseEnvelope(
    val command: Int,
    val requestId: Long,
    val status: Int,
    val errorCode: Int,
    val errorMessage: String,
    val payload: Bundle,
) {
    val isSuccess: Boolean
        get() = status == IpcContract.Status.SUCCESS
}

object IpcEnvelopeCodec {
    fun request(
        command: Int,
        requestId: Long,
        payload: Bundle,
        replyTo: Messenger,
    ): Message {
        require(IpcContract.Command.isKnown(command))
        require(requestId > 0)

        return Message.obtain(null, command).apply {
            data = Bundle(payload).apply {
                putInt(IpcContract.Key.VERSION, IpcContract.VERSION)
                putLong(IpcContract.Key.REQUEST_ID, requestId)
                putInt(IpcContract.Key.COMMAND, command)
            }
            this.replyTo = replyTo
        }
    }

    fun decodeRequest(message: Message): RequestEnvelope {
        val payload = message.data ?: Bundle.EMPTY
        val version = payload.requireInt(IpcContract.Key.VERSION, minimum = 1)
        if (version != IpcContract.VERSION) {
            throw ProtocolException(
                IpcContract.Error.UNSUPPORTED_PROTOCOL,
                "Unsupported protocol version: $version",
            )
        }

        val requestId = payload.requireLong(IpcContract.Key.REQUEST_ID, minimum = 1)
        val command = payload.requireInt(IpcContract.Key.COMMAND)
        if (command != message.what) {
            throw ProtocolException(IpcContract.Error.MALFORMED_REQUEST, "Command envelope mismatch")
        }
        if (!IpcContract.Command.isKnown(command)) {
            throw ProtocolException(IpcContract.Error.UNKNOWN_COMMAND, "Unknown command: $command")
        }
        val replyTo = message.replyTo
            ?: throw ProtocolException(IpcContract.Error.MALFORMED_REQUEST, "Missing reply Messenger")

        return RequestEnvelope(command, requestId, payload, replyTo)
    }

    fun success(command: Int, requestId: Long, payload: Bundle = Bundle()): Message =
        response(
            command = command,
            requestId = requestId,
            status = IpcContract.Status.SUCCESS,
            errorCode = IpcContract.Error.NONE,
            errorMessage = "",
            payload = payload,
        )

    fun error(command: Int, requestId: Long, errorCode: Int, errorMessage: String): Message =
        response(
            command = command,
            requestId = requestId,
            status = IpcContract.Status.ERROR,
            errorCode = errorCode,
            errorMessage = errorMessage.take(IpcContract.MAX_ERROR_LENGTH),
            payload = Bundle(),
        )

    fun decodeResponse(message: Message): ResponseEnvelope {
        val payload = message.data ?: Bundle.EMPTY
        val version = payload.requireInt(IpcContract.Key.VERSION, minimum = 1)
        if (version != IpcContract.VERSION) {
            throw ProtocolException(
                IpcContract.Error.UNSUPPORTED_PROTOCOL,
                "Unsupported protocol version: $version",
            )
        }

        val requestId = payload.requireLong(IpcContract.Key.REQUEST_ID, minimum = 1)
        val command = payload.requireInt(IpcContract.Key.COMMAND)
        if (command != message.what || !IpcContract.Command.isKnown(command)) {
            throw ProtocolException(IpcContract.Error.MALFORMED_REQUEST, "Invalid response command")
        }

        val status = payload.requireInt(IpcContract.Key.STATUS)
        if (!IpcContract.Status.isKnown(status)) {
            throw ProtocolException(IpcContract.Error.MALFORMED_REQUEST, "Invalid response status")
        }
        val errorCode = payload.requireInt(IpcContract.Key.ERROR_CODE, minimum = 0)
        val errorMessage = payload.requireString(
            IpcContract.Key.ERROR_MESSAGE,
            IpcContract.MAX_ERROR_LENGTH,
        )
        if (status == IpcContract.Status.SUCCESS && errorCode != IpcContract.Error.NONE) {
            throw ProtocolException(IpcContract.Error.MALFORMED_REQUEST, "Successful response has an error")
        }
        if (status == IpcContract.Status.ERROR && errorCode == IpcContract.Error.NONE) {
            throw ProtocolException(IpcContract.Error.MALFORMED_REQUEST, "Error response has no error code")
        }

        return ResponseEnvelope(command, requestId, status, errorCode, errorMessage, payload)
    }

    private fun response(
        command: Int,
        requestId: Long,
        status: Int,
        errorCode: Int,
        errorMessage: String,
        payload: Bundle,
    ): Message = Message.obtain(null, command).apply {
        data = Bundle(payload).apply {
            putInt(IpcContract.Key.VERSION, IpcContract.VERSION)
            putLong(IpcContract.Key.REQUEST_ID, requestId)
            putInt(IpcContract.Key.COMMAND, command)
            putInt(IpcContract.Key.STATUS, status)
            putInt(IpcContract.Key.ERROR_CODE, errorCode)
            putString(IpcContract.Key.ERROR_MESSAGE, errorMessage)
        }
    }
}
