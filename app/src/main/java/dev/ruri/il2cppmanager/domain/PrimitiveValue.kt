package dev.ruri.il2cppmanager.domain

import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class ValueKind(
    val wireValue: Int,
    val byteSize: Int,
    val writable: Boolean,
) {
    BOOLEAN(1, Byte.SIZE_BYTES, true),
    INT32(2, Int.SIZE_BYTES, true),
    INT64(3, Long.SIZE_BYTES, true),
    FLOAT32(4, Float.SIZE_BYTES, true),
    FLOAT64(5, Double.SIZE_BYTES, true),
    STRING(6, Long.SIZE_BYTES, false),
    UNRESOLVED(7, 0, false),
    ;

    companion object {
        fun fromWireValue(value: Int): ValueKind? = entries.firstOrNull { it.wireValue == value }

        fun fromTypeName(typeName: String?): ValueKind {
            val normalized = typeName
                ?.trim()
                ?.removePrefix("System.")
                ?.lowercase()
                ?: return UNRESOLVED

            return when (normalized) {
                "bool", "boolean" -> BOOLEAN
                "int", "int32" -> INT32
                "long", "int64" -> INT64
                "float", "single" -> FLOAT32
                "double" -> FLOAT64
                "string" -> STRING
                else -> UNRESOLVED
            }
        }
    }
}

sealed interface PrimitiveValue {
    val kind: ValueKind

    data class BooleanValue(val value: Boolean) : PrimitiveValue {
        override val kind = ValueKind.BOOLEAN
    }

    data class Int32Value(val value: Int) : PrimitiveValue {
        override val kind = ValueKind.INT32
    }

    data class Int64Value(val value: Long) : PrimitiveValue {
        override val kind = ValueKind.INT64
    }

    data class Float32Value(val value: Float) : PrimitiveValue {
        override val kind = ValueKind.FLOAT32
    }

    data class Float64Value(val value: Double) : PrimitiveValue {
        override val kind = ValueKind.FLOAT64
    }
}

object PrimitiveCodec {
    fun encode(value: PrimitiveValue): ByteArray = when (value) {
        is PrimitiveValue.BooleanValue -> byteArrayOf(if (value.value) 1 else 0)
        is PrimitiveValue.Int32Value -> buffer(Int.SIZE_BYTES).putInt(value.value).array()
        is PrimitiveValue.Int64Value -> buffer(Long.SIZE_BYTES).putLong(value.value).array()
        is PrimitiveValue.Float32Value -> buffer(Float.SIZE_BYTES).putFloat(value.value).array()
        is PrimitiveValue.Float64Value -> buffer(Double.SIZE_BYTES).putDouble(value.value).array()
    }

    fun decode(kind: ValueKind, bytes: ByteArray): PrimitiveValue? {
        if (!kind.writable || bytes.size != kind.byteSize) {
            return null
        }

        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return when (kind) {
            ValueKind.BOOLEAN -> PrimitiveValue.BooleanValue(bytes[0].toInt() != 0)
            ValueKind.INT32 -> PrimitiveValue.Int32Value(buffer.int)
            ValueKind.INT64 -> PrimitiveValue.Int64Value(buffer.long)
            ValueKind.FLOAT32 -> PrimitiveValue.Float32Value(buffer.float)
            ValueKind.FLOAT64 -> PrimitiveValue.Float64Value(buffer.double)
            ValueKind.STRING,
            ValueKind.UNRESOLVED,
            -> null
        }
    }

    fun display(value: PrimitiveValue): String = when (value) {
        is PrimitiveValue.BooleanValue -> value.value.toString()
        is PrimitiveValue.Int32Value -> value.value.toString()
        is PrimitiveValue.Int64Value -> value.value.toString()
        is PrimitiveValue.Float32Value -> value.value.toString()
        is PrimitiveValue.Float64Value -> value.value.toString()
    }

    private fun buffer(size: Int): ByteBuffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
}
