package dev.ruri.il2cppmanager.root

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.RemoteException
import android.util.Log
import com.topjohnwu.superuser.ipc.RootService
import dev.ruri.il2cppmanager.ipc.IpcContract
import dev.ruri.il2cppmanager.ipc.IpcEnvelopeCodec
import dev.ruri.il2cppmanager.ipc.ProtocolException
import dev.ruri.il2cppmanager.ipc.optionalCommand
import dev.ruri.il2cppmanager.ipc.optionalRequestId
import dev.ruri.il2cppmanager.nativebridge.NativeEngine
import dev.ruri.il2cppmanager.nativebridge.NativeEngineUnavailableException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class Il2CppRootService : RootService() {
    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null
    private var serviceMessenger: Messenger? = null
    private var dispatcher: RootCommandDispatcher? = null
    private var nativeLoadFailure: Throwable? = null

    override fun onCreate() {
        super.onCreate()
        nativeLoadFailure = runCatching { NativeEngine.ensureLoaded() }.exceptionOrNull()
        dispatcher = RootCommandDispatcher()
        workerThread = HandlerThread(WORKER_THREAD_NAME, Process.THREAD_PRIORITY_BACKGROUND).also {
            it.start()
            workerHandler = Handler(it.looper, Handler.Callback(::receiveMessage))
            serviceMessenger = Messenger(requireNotNull(workerHandler))
        }
    }

    override fun onBind(intent: Intent): IBinder = requireNotNull(serviceMessenger).binder

    override fun onDestroy() {
        val handler = workerHandler
        val thread = workerThread
        val activeDispatcher = dispatcher

        workerHandler = null
        serviceMessenger = null
        dispatcher = null
        nativeLoadFailure = null

        handler?.removeCallbacksAndMessages(null)
        if (handler != null && thread != null && activeDispatcher != null) {
            closeDispatcher(handler, thread, activeDispatcher)
        } else {
            runCatching { activeDispatcher?.close() }
                .onFailure { Log.e(LOG_TAG, "Native session cleanup failed", it) }
        }
        thread?.quitSafely()
        if (thread != null && Thread.currentThread() !== thread) {
            runCatching { thread.join(WORKER_JOIN_TIMEOUT_MILLIS) }
                .onFailure { Log.e(LOG_TAG, "Root worker shutdown failed", it) }
        }
        workerThread = null
        super.onDestroy()
    }

    private fun receiveMessage(message: Message): Boolean {
        val fallbackPayload = runCatching { message.data }.getOrDefault(Bundle.EMPTY)
        val fallbackCommand = fallbackPayload.optionalCommand(message.what)
        val fallbackRequestId = fallbackPayload.optionalRequestId()
        val replyTo = message.replyTo

        val response = try {
            val request = IpcEnvelopeCodec.decodeRequest(message)
            nativeLoadFailure?.let {
                throw ServiceFault(
                    IpcContract.Error.NATIVE_UNAVAILABLE,
                    "Native engine is unavailable",
                    it,
                )
            }
            val result = requireNotNull(dispatcher).handle(request.command, request.payload)
            IpcEnvelopeCodec.success(request.command, request.requestId, result)
        } catch (error: Throwable) {
            val failure = mapFailure(error)
            IpcEnvelopeCodec.error(
                command = fallbackCommand,
                requestId = fallbackRequestId,
                errorCode = failure.errorCode,
                errorMessage = failure.message,
            )
        }
        sendReply(replyTo, response)
        return true
    }

    private fun closeDispatcher(
        handler: Handler,
        thread: HandlerThread,
        activeDispatcher: RootCommandDispatcher,
    ) {
        if (Thread.currentThread() === thread) {
            runCatching(activeDispatcher::close)
                .onFailure { Log.e(LOG_TAG, "Native session cleanup failed", it) }
            return
        }

        val closed = CountDownLatch(1)
        val posted = handler.post {
            try {
                activeDispatcher.close()
            } catch (error: Throwable) {
                Log.e(LOG_TAG, "Native session cleanup failed", error)
            } finally {
                closed.countDown()
            }
        }
        if (!posted) {
            runCatching(activeDispatcher::close)
                .onFailure { Log.e(LOG_TAG, "Native session cleanup failed", it) }
            return
        }
        runCatching { closed.await(CLEANUP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS) }
            .onFailure { Log.e(LOG_TAG, "Native session cleanup wait failed", it) }
    }

    private fun sendReply(replyTo: Messenger?, response: Message) {
        if (replyTo == null) {
            Log.w(LOG_TAG, "Dropping response without a reply Messenger")
            return
        }
        try {
            replyTo.send(response)
        } catch (error: RemoteException) {
            Log.w(LOG_TAG, "Unable to deliver root service response", error)
        }
    }

    private fun mapFailure(error: Throwable): Failure = when (error) {
        is ServiceFault -> Failure(error.errorCode, error.message.orEmpty())
        is ProtocolException -> Failure(error.errorCode, error.message.orEmpty())
        is NativeEngineUnavailableException,
        is UnsatisfiedLinkError,
        -> Failure(IpcContract.Error.NATIVE_UNAVAILABLE, "Native engine is unavailable")
        is SecurityException -> Failure(IpcContract.Error.MEMORY_ACCESS_DENIED, "Permission denied")
        else -> {
            Log.e(LOG_TAG, "Unhandled root service failure", error)
            Failure(IpcContract.Error.INTERNAL, "Internal root service failure")
        }
    }

    private data class Failure(val errorCode: Int, val message: String)

    private companion object {
        const val LOG_TAG = "Il2CppRootService"
        const val WORKER_THREAD_NAME = "il2cpp-root-worker"
        const val CLEANUP_TIMEOUT_MILLIS = 3_000L
        const val WORKER_JOIN_TIMEOUT_MILLIS = 3_000L
    }
}
