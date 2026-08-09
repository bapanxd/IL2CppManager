package dev.ruri.il2cppmanager.root

internal class ServiceFault(
    val errorCode: Int,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
