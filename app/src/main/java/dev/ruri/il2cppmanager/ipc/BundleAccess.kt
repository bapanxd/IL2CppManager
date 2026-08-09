package dev.ruri.il2cppmanager.ipc

import android.os.Bundle

internal fun Bundle.requireInt(
    key: String,
    minimum: Int = Int.MIN_VALUE,
    maximum: Int = Int.MAX_VALUE,
): Int {
    val value = rawValue(key) as? Int ?: malformed(key)
    if (value !in minimum..maximum) {
        malformed(key)
    }
    return value
}

internal fun Bundle.requireLong(
    key: String,
    minimum: Long = Long.MIN_VALUE,
    maximum: Long = Long.MAX_VALUE,
): Long {
    val value = rawValue(key) as? Long ?: malformed(key)
    if (value < minimum || value > maximum) {
        malformed(key)
    }
    return value
}

internal fun Bundle.requireBoolean(key: String): Boolean = rawValue(key) as? Boolean ?: malformed(key)

internal fun Bundle.requireFloat(key: String): Float = rawValue(key) as? Float ?: malformed(key)

internal fun Bundle.requireDouble(key: String): Double = rawValue(key) as? Double ?: malformed(key)

internal fun Bundle.requireString(key: String, maximumLength: Int): String {
    val value = rawValue(key) as? String ?: malformed(key)
    if (value.length > maximumLength) {
        malformed(key)
    }
    return value
}

internal fun Bundle.requireIntArray(key: String, maximumSize: Int): IntArray {
    val value = rawValue(key) as? IntArray ?: malformed(key)
    if (value.size > maximumSize) {
        malformed(key)
    }
    return value
}

internal fun Bundle.requireLongArray(key: String, maximumSize: Int): LongArray {
    val value = rawValue(key) as? LongArray ?: malformed(key)
    if (value.size > maximumSize) {
        malformed(key)
    }
    return value
}

internal fun Bundle.requireBooleanArray(key: String, maximumSize: Int): BooleanArray {
    val value = rawValue(key) as? BooleanArray ?: malformed(key)
    if (value.size > maximumSize) {
        malformed(key)
    }
    return value
}

internal fun Bundle.requireStringArray(
    key: String,
    maximumSize: Int,
    maximumItemLength: Int,
): Array<String> {
    val value = rawValue(key) as? Array<*> ?: malformed(key)
    if (value.javaClass.componentType != String::class.java || value.size > maximumSize) {
        malformed(key)
    }
    value.indices.forEach { index ->
        val item = value[index] as? String ?: malformed(key)
        if (item.length > maximumItemLength) {
            malformed(key)
        }
    }
    @Suppress("UNCHECKED_CAST")
    return value as Array<String>
}

internal fun Bundle.optionalRequestId(): Long = (rawValueOrNull(IpcContract.Key.REQUEST_ID) as? Long) ?: 0L

internal fun Bundle.optionalCommand(fallback: Int): Int =
    (rawValueOrNull(IpcContract.Key.COMMAND) as? Int) ?: fallback

@Suppress("DEPRECATION")
private fun Bundle.rawValue(key: String): Any = get(key) ?: malformed(key)

@Suppress("DEPRECATION")
private fun Bundle.rawValueOrNull(key: String): Any? = get(key)

private fun malformed(key: String): Nothing = throw ProtocolException(
    IpcContract.Error.MALFORMED_REQUEST,
    "Invalid or missing payload key: $key",
)
