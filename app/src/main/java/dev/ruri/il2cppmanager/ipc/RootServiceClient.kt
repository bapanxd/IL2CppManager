package dev.ruri.il2cppmanager.ipc

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.util.Log
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ipc.RootService
import dev.ruri.il2cppmanager.domain.AssemblyDescriptor
import dev.ruri.il2cppmanager.domain.ClassDescriptor
import dev.ruri.il2cppmanager.domain.ClassInfoDescriptor
import dev.ruri.il2cppmanager.domain.EventDescriptor
import dev.ruri.il2cppmanager.domain.FieldDescriptor
import dev.ruri.il2cppmanager.domain.FieldReadResult
import dev.ruri.il2cppmanager.domain.InstructionDescriptor
import dev.ruri.il2cppmanager.domain.MemberKind
import dev.ruri.il2cppmanager.domain.MethodAnalysisResult
import dev.ruri.il2cppmanager.domain.MethodAnalysisSection
import dev.ruri.il2cppmanager.domain.MethodDescriptor
import dev.ruri.il2cppmanager.domain.MethodReferenceDescriptor
import dev.ruri.il2cppmanager.domain.NamespaceDescriptor
import dev.ruri.il2cppmanager.domain.PageResult
import dev.ruri.il2cppmanager.domain.PrimitiveValue
import dev.ruri.il2cppmanager.domain.ProcessDescriptor
import dev.ruri.il2cppmanager.domain.PropertyDescriptor
import dev.ruri.il2cppmanager.domain.SearchMatchMode
import dev.ruri.il2cppmanager.domain.SymbolSearchDescriptor
import dev.ruri.il2cppmanager.domain.TypeReferenceDescriptor
import dev.ruri.il2cppmanager.domain.TypeSearchDescriptor
import dev.ruri.il2cppmanager.root.Il2CppRootService
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

class RootServiceClient(
    context: Context,
    private val requestTimeoutMillis: Long = IpcContract.DEFAULT_REQUEST_TIMEOUT_MILLIS,
    private val openTargetTimeoutMillis: Long = IpcContract.OPEN_TARGET_REQUEST_TIMEOUT_MILLIS,
    private val methodAnalysisTimeoutMillis: Long =
        IpcContract.METHOD_ANALYSIS_REQUEST_TIMEOUT_MILLIS,
    private val connectionTimeoutMillis: Long = IpcContract.DEFAULT_CONNECTION_TIMEOUT_MILLIS,
) : AutoCloseable {
    private val applicationContext = context.applicationContext
    private val stateLock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val requestIds = AtomicLong(FIRST_REQUEST_ID)
    private val pendingRequests = ConcurrentHashMap<Long, PendingRequest>()
    private val replyHandler = Handler(Looper.getMainLooper(), Handler.Callback(::receiveReply))
    private val replyMessenger = Messenger(replyHandler)
    private val serviceIntent = Intent(applicationContext, Il2CppRootService::class.java)

    @Volatile
    private var closed = false

    private var remoteService: Messenger? = null
    private var activeConnection: ConnectionAttempt? = null

    init {
        require(requestTimeoutMillis in 1..MAX_REQUEST_TIMEOUT_MILLIS)
        require(openTargetTimeoutMillis in 1..MAX_REQUEST_TIMEOUT_MILLIS)
        require(methodAnalysisTimeoutMillis in 1..MAX_REQUEST_TIMEOUT_MILLIS)
        require(connectionTimeoutMillis in 1..MAX_REQUEST_TIMEOUT_MILLIS)
    }

    suspend fun scanProcesses(
        offset: Int = 0,
        limit: Int = IpcContract.DEFAULT_PAGE_SIZE,
    ): PageResult<ProcessDescriptor> = ResponsePayloadCodec.decodeProcessPage(
        request(
            IpcContract.Command.SCAN_PROCESSES,
            RequestPayloadCodec.scanProcesses(offset, limit),
        ),
    )

    suspend fun openTarget(pid: Int, startTicks: Long) {
        request(
            IpcContract.Command.OPEN_TARGET,
            RequestPayloadCodec.openTarget(pid, startTicks),
        )
    }

    suspend fun listAssemblies(
        offset: Int = 0,
        limit: Int = IpcContract.DEFAULT_PAGE_SIZE,
    ): PageResult<AssemblyDescriptor> = ResponsePayloadCodec.decodeAssemblyPage(
        request(
            IpcContract.Command.LIST_ASSEMBLIES,
            RequestPayloadCodec.listAssemblies(offset, limit),
        ),
    )

    suspend fun listNamespaces(
        assemblyIndex: Int,
        offset: Int = 0,
        limit: Int = IpcContract.DEFAULT_PAGE_SIZE,
    ): PageResult<NamespaceDescriptor> = ResponsePayloadCodec.decodeNamespacePage(
        request(
            IpcContract.Command.LIST_NAMESPACES,
            RequestPayloadCodec.listNamespaces(assemblyIndex, offset, limit),
        ),
    )

    suspend fun listClasses(
        assemblyIndex: Int,
        namespaceIndex: Int,
        offset: Int = 0,
        limit: Int = IpcContract.DEFAULT_PAGE_SIZE,
    ): PageResult<ClassDescriptor> = ResponsePayloadCodec.decodeClassPage(
        request(
            IpcContract.Command.LIST_CLASSES,
            RequestPayloadCodec.listClasses(assemblyIndex, namespaceIndex, offset, limit),
        ),
    )

    suspend fun searchTypes(
        assemblyIndex: Int,
        query: String,
        matchMode: SearchMatchMode,
        matchCase: Boolean,
        offset: Int = 0,
        limit: Int = IpcContract.SEARCH_PAGE_SIZE,
    ): PageResult<TypeSearchDescriptor> = ResponsePayloadCodec.decodeTypeSearchPage(
        request(
            IpcContract.Command.SEARCH_TYPES,
            RequestPayloadCodec.searchTypes(
                assemblyIndex,
                query,
                matchMode,
                matchCase,
                offset,
                limit,
            ),
        ),
    )

    suspend fun searchSymbols(
        query: String,
        matchMode: SearchMatchMode,
        matchCase: Boolean,
        offset: Int = 0,
        limit: Int = IpcContract.SEARCH_PAGE_SIZE,
    ): PageResult<SymbolSearchDescriptor> = ResponsePayloadCodec.decodeSymbolSearchPage(
        request(
            IpcContract.Command.SEARCH_SYMBOLS,
            RequestPayloadCodec.searchSymbols(
                query,
                matchMode,
                matchCase,
                offset,
                limit,
            ),
        ),
    )

    suspend fun listFields(
        classIndex: Int,
        offset: Int = 0,
        limit: Int = IpcContract.DEFAULT_PAGE_SIZE,
    ): PageResult<FieldDescriptor> = ResponsePayloadCodec.decodeFieldPage(
        request(
            IpcContract.Command.CLASS_MEMBERS,
            RequestPayloadCodec.classMembers(classIndex, MemberKind.FIELD, offset, limit),
        ),
    )

    suspend fun classInfo(classIndex: Int): ClassInfoDescriptor =
        ResponsePayloadCodec.decodeClassInfo(
            request(
                IpcContract.Command.CLASS_INFO,
                RequestPayloadCodec.classInfo(classIndex),
            ),
        )

    suspend fun listProperties(
        classIndex: Int,
        offset: Int = 0,
        limit: Int = IpcContract.DEFAULT_PAGE_SIZE,
    ): PageResult<PropertyDescriptor> = ResponsePayloadCodec.decodePropertyPage(
        request(
            IpcContract.Command.CLASS_MEMBERS,
            RequestPayloadCodec.classMembers(classIndex, MemberKind.PROPERTY, offset, limit),
        ),
    )

    suspend fun listEvents(
        classIndex: Int,
        offset: Int = 0,
        limit: Int = IpcContract.DEFAULT_PAGE_SIZE,
    ): PageResult<EventDescriptor> = ResponsePayloadCodec.decodeEventPage(
        request(
            IpcContract.Command.CLASS_MEMBERS,
            RequestPayloadCodec.classMembers(classIndex, MemberKind.EVENT, offset, limit),
        ),
    )

    suspend fun listNestedTypes(
        classIndex: Int,
        offset: Int = 0,
        limit: Int = IpcContract.DEFAULT_PAGE_SIZE,
    ): PageResult<ClassDescriptor> = ResponsePayloadCodec.decodeNestedTypePage(
        request(
            IpcContract.Command.CLASS_MEMBERS,
            RequestPayloadCodec.classMembers(classIndex, MemberKind.NESTED_TYPE, offset, limit),
        ),
    )

    suspend fun listInterfaces(
        classIndex: Int,
        offset: Int = 0,
        limit: Int = IpcContract.DEFAULT_PAGE_SIZE,
    ): PageResult<TypeReferenceDescriptor> = ResponsePayloadCodec.decodeInterfacePage(
        request(
            IpcContract.Command.CLASS_MEMBERS,
            RequestPayloadCodec.classMembers(classIndex, MemberKind.INTERFACE, offset, limit),
        ),
    )

    suspend fun listMethods(
        classIndex: Int,
        offset: Int = 0,
        limit: Int = IpcContract.DEFAULT_PAGE_SIZE,
    ): PageResult<MethodDescriptor> = ResponsePayloadCodec.decodeMethodPage(
        request(
            IpcContract.Command.CLASS_MEMBERS,
            RequestPayloadCodec.classMembers(classIndex, MemberKind.METHOD, offset, limit),
        ),
    )

    suspend fun methodCalls(
        classIndex: Int,
        methodIndex: Int,
        offset: Int = 0,
        limit: Int = IpcContract.MAX_ANALYSIS_PAGE_SIZE,
    ): MethodAnalysisResult<MethodReferenceDescriptor> = methodReferences(
        classIndex,
        methodIndex,
        MethodAnalysisSection.CALLS,
        offset,
        limit,
    )

    suspend fun methodCallers(
        classIndex: Int,
        methodIndex: Int,
        offset: Int = 0,
        limit: Int = IpcContract.MAX_ANALYSIS_PAGE_SIZE,
    ): MethodAnalysisResult<MethodReferenceDescriptor> = methodReferences(
        classIndex,
        methodIndex,
        MethodAnalysisSection.CALLERS,
        offset,
        limit,
    )

    suspend fun methodInstructions(
        classIndex: Int,
        methodIndex: Int,
        offset: Int = 0,
        limit: Int = IpcContract.MAX_ANALYSIS_PAGE_SIZE,
    ): MethodAnalysisResult<InstructionDescriptor> =
        ResponsePayloadCodec.decodeInstructionAnalysis(
            request(
                IpcContract.Command.METHOD_ANALYSIS,
                RequestPayloadCodec.methodAnalysis(
                    classIndex,
                    methodIndex,
                    MethodAnalysisSection.INSTRUCTIONS,
                    offset,
                    limit,
                ),
            ),
        )

    suspend fun readVisibleFields(
        classIndex: Int,
        objectAddress: Long,
        fieldIndices: IntArray,
    ): List<FieldReadResult> = ResponsePayloadCodec.decodeFieldReads(
        request(
            IpcContract.Command.READ_VISIBLE_FIELDS,
            RequestPayloadCodec.readVisibleFields(classIndex, objectAddress, fieldIndices),
        ),
    )

    suspend fun writePrimitive(
        classIndex: Int,
        objectAddress: Long,
        fieldIndex: Int,
        value: PrimitiveValue,
    ): Int = ResponsePayloadCodec.decodeWriteResult(
        request(
            IpcContract.Command.WRITE_PRIMITIVE,
            RequestPayloadCodec.writePrimitive(classIndex, objectAddress, fieldIndex, value),
        ),
    )

    suspend fun closeTarget() {
        request(IpcContract.Command.CLOSE_TARGET, RequestPayloadCodec.closeTarget())
    }

    fun disconnect() {
        disconnectPermanently(permanent = false)
    }

    override fun close() {
        disconnectPermanently(permanent = true)
        synchronized(INSTANCE_LOCK) {
            if (instance === this) {
                instance = null
            }
        }
    }

    private suspend fun methodReferences(
        classIndex: Int,
        methodIndex: Int,
        section: MethodAnalysisSection,
        offset: Int,
        limit: Int,
    ): MethodAnalysisResult<MethodReferenceDescriptor> =
        ResponsePayloadCodec.decodeMethodReferenceAnalysis(
            request(
                IpcContract.Command.METHOD_ANALYSIS,
                RequestPayloadCodec.methodAnalysis(
                    classIndex,
                    methodIndex,
                    section,
                    offset,
                    limit,
                ),
            ),
        )

    private suspend fun request(command: Int, payload: Bundle): Bundle {
        val service = awaitService()
        val timeoutMillis = when (command) {
            IpcContract.Command.OPEN_TARGET -> openTargetTimeoutMillis
            IpcContract.Command.METHOD_ANALYSIS -> methodAnalysisTimeoutMillis
            else -> requestTimeoutMillis
        }
        return withTimeoutOrNull(timeoutMillis) {
            executeRequest(service, command, payload)
        } ?: throw RemoteServiceException(
            IpcContract.Error.TIMEOUT,
            when (command) {
                IpcContract.Command.OPEN_TARGET -> "Opening target timed out"
                IpcContract.Command.METHOD_ANALYSIS -> "Method analysis timed out"
                else -> "Root service request timed out"
            },
        )
    }

    private suspend fun executeRequest(
        service: Messenger,
        command: Int,
        payload: Bundle,
    ): Bundle {
        val requestId = nextRequestId()
        val pending = PendingRequest(command, CompletableDeferred())
        check(pendingRequests.putIfAbsent(requestId, pending) == null)
        try {
            if (!isCurrentService(service)) {
                throw RemoteServiceException(
                    IpcContract.Error.SERVICE_DISCONNECTED,
                    "Root service disconnected",
                )
            }
            val message = IpcEnvelopeCodec.request(command, requestId, payload, replyMessenger)
            try {
                service.send(message)
            } catch (error: RemoteException) {
                handleTransportFailure(service, error)
                throw RemoteServiceException(
                    IpcContract.Error.SERVICE_DISCONNECTED,
                    "Root service disconnected",
                )
            }

            val response = pending.response.await()
            if (!response.isSuccess) {
                throw RemoteServiceException(response.errorCode, response.errorMessage)
            }
            return response.payload
        } finally {
            pendingRequests.remove(requestId, pending)
            pending.response.cancel()
        }
    }

    private suspend fun awaitService(): Messenger {
        var shouldBind = false
        val attempt = synchronized(stateLock) {
            check(!closed) { "RootServiceClient is closed" }
            remoteService?.let { return it }
            activeConnection ?: ConnectionAttempt().also {
                activeConnection = it
                shouldBind = true
            }
        }
        if (shouldBind) {
            val posted = mainHandler.post { bind(attempt) }
            if (!posted) {
                failConnectionAttempt(
                    attempt,
                    RemoteServiceException(
                        IpcContract.Error.SERVICE_DISCONNECTED,
                        "Main looper is unavailable",
                    ),
                )
            }
        }
        val service = withTimeoutOrNull(connectionTimeoutMillis) { attempt.waiter.await() }
        if (service != null) {
            return service
        }
        val failure = RemoteServiceException(
            IpcContract.Error.TIMEOUT,
            "Root service connection timed out",
        )
        failConnectionAttempt(attempt, failure)
        throw failure
    }

    private fun bind(attempt: ConnectionAttempt) {
        val proceed = synchronized(stateLock) {
            !closed && activeConnection === attempt && remoteService == null
        }
        if (!proceed) {
            return
        }

        if (Shell.isAppGrantedRoot() == false) {
            failConnectionAttempt(
                attempt,
                RemoteServiceException(
                    IpcContract.Error.SERVICE_DISCONNECTED,
                    "Root access was denied",
                ),
            )
            return
        }
        try {
            RootService.bind(serviceIntent, attempt.serviceConnection)
        } catch (error: Throwable) {
            failConnectionAttempt(
                attempt,
                RemoteServiceException(
                    IpcContract.Error.SERVICE_DISCONNECTED,
                    error.message ?: "Root service binding failed",
                ),
            )
        }
    }

    private fun failConnectionAttempt(
        attempt: ConnectionAttempt,
        failure: RemoteServiceException,
    ) {
        val removed = synchronized(stateLock) {
            if (activeConnection !== attempt || remoteService != null) {
                false
            } else {
                activeConnection = null
                true
            }
        }
        if (!removed) {
            return
        }
        attempt.waiter.completeExceptionally(failure)
        scheduleUnbind(attempt)
    }

    private fun receiveReply(message: Message): Boolean {
        val response = try {
            IpcEnvelopeCodec.decodeResponse(message)
        } catch (error: Throwable) {
            Log.w(LOG_TAG, "Rejected malformed root service reply", error)
            return true
        }
        val pending = pendingRequests.remove(response.requestId)
        if (pending == null) {
            Log.w(LOG_TAG, "Rejected stale root service reply")
            return true
        }
        if (pending.command != response.command) {
            pending.response.completeExceptionally(
                ProtocolException(
                    IpcContract.Error.MALFORMED_REQUEST,
                    "Reply command does not match request",
                ),
            )
            return true
        }
        pending.response.complete(response)
        return true
    }

    private fun handleTransportFailure(service: Messenger, error: RemoteException) {
        val attempt = synchronized(stateLock) {
            activeConnection?.takeIf { remoteService === service }
        }
        if (attempt != null) {
            disconnectConnection(
                attempt,
                RemoteServiceException(
                    IpcContract.Error.SERVICE_DISCONNECTED,
                    error.message ?: "Root service disconnected",
                ),
            )
        }
    }

    private fun disconnectPermanently(permanent: Boolean) {
        val failure = RemoteServiceException(
            IpcContract.Error.SERVICE_DISCONNECTED,
            if (permanent) "Root service client closed" else "Root service disconnected",
        )
        val attempt = synchronized(stateLock) {
            if (closed) {
                return
            }
            if (permanent) {
                closed = true
            }
            remoteService = null
            activeConnection.also { activeConnection = null }
        }
        attempt?.waiter?.completeExceptionally(failure)
        failPending(failure)
        if (attempt != null) {
            scheduleUnbind(attempt)
        }
    }

    private fun disconnectConnection(
        attempt: ConnectionAttempt,
        failure: RemoteServiceException,
    ) {
        val removed = synchronized(stateLock) {
            if (activeConnection !== attempt) {
                false
            } else {
                remoteService = null
                activeConnection = null
                true
            }
        }
        if (!removed) {
            return
        }
        attempt.waiter.completeExceptionally(failure)
        failPending(failure)
        scheduleUnbind(attempt)
    }

    private fun failPending(error: Throwable) {
        pendingRequests.entries.forEach { entry ->
            if (pendingRequests.remove(entry.key, entry.value)) {
                entry.value.response.completeExceptionally(error)
            }
        }
    }

    private fun isCurrentService(service: Messenger): Boolean = synchronized(stateLock) {
        !closed && remoteService === service
    }

    private fun handleServiceConnected(attempt: ConnectionAttempt, service: IBinder) {
        val messenger = Messenger(service)
        var shouldUnbind = false
        val shouldComplete = synchronized(stateLock) {
            when {
                closed || activeConnection !== attempt -> {
                    shouldUnbind = true
                    false
                }

                remoteService == null -> {
                    remoteService = messenger
                    true
                }

                else -> false
            }
        }
        if (shouldUnbind) {
            scheduleUnbind(attempt)
        } else if (shouldComplete) {
            attempt.waiter.complete(messenger)
        }
    }

    private fun scheduleUnbind(attempt: ConnectionAttempt) {
        if (Looper.myLooper() === mainHandler.looper) {
            safeUnbind(attempt.serviceConnection)
            return
        }
        if (!mainHandler.post { safeUnbind(attempt.serviceConnection) }) {
            Log.w(LOG_TAG, "Main looper rejected root service unbind")
        }
    }

    private fun safeUnbind(connection: ServiceConnection) {
        try {
            RootService.unbind(connection)
        } catch (error: Throwable) {
            Log.w(LOG_TAG, "Root service unbind failed", error)
        }
    }

    private fun nextRequestId(): Long {
        val requestId = requestIds.getAndIncrement()
        check(requestId > 0) { "Root service request ID space exhausted" }
        return requestId
    }

    private inner class ConnectionAttempt {
        val waiter = CompletableDeferred<Messenger>()
        val serviceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                handleServiceConnected(this@ConnectionAttempt, service)
            }

            override fun onServiceDisconnected(name: ComponentName) {
                disconnectConnection(
                    this@ConnectionAttempt,
                    RemoteServiceException(
                        IpcContract.Error.SERVICE_DISCONNECTED,
                        "Root service disconnected",
                    ),
                )
            }

            override fun onBindingDied(name: ComponentName) {
                disconnectConnection(
                    this@ConnectionAttempt,
                    RemoteServiceException(
                        IpcContract.Error.SERVICE_DISCONNECTED,
                        "Root service binding died",
                    ),
                )
            }

            override fun onNullBinding(name: ComponentName) {
                disconnectConnection(
                    this@ConnectionAttempt,
                    RemoteServiceException(
                        IpcContract.Error.SERVICE_DISCONNECTED,
                        "Root service returned no binder",
                    ),
                )
            }
        }
    }

    private data class PendingRequest(
        val command: Int,
        val response: CompletableDeferred<ResponseEnvelope>,
    )

    companion object {
        private const val LOG_TAG = "RootServiceClient"
        private const val FIRST_REQUEST_ID = 1L
        private const val MAX_REQUEST_TIMEOUT_MILLIS = 120_000L
        private val INSTANCE_LOCK = Any()

        @Volatile
        private var instance: RootServiceClient? = null

        fun getInstance(context: Context): RootServiceClient = instance ?: synchronized(INSTANCE_LOCK) {
            instance ?: RootServiceClient(context.applicationContext).also { instance = it }
        }
    }
}
