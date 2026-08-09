package dev.ruri.il2cppmanager.ipc

object IpcContract {
    const val VERSION = 8
    const val DEFAULT_PAGE_SIZE = 100
    const val MAX_PAGE_SIZE = 200
    const val SEARCH_PAGE_SIZE = 32
    const val MAX_ANALYSIS_PAGE_SIZE = 64
    const val MAX_PROCESS_SCAN_COUNT = 32_768
    const val MAX_HIERARCHY_COUNT = 5_000_000
    const val MAX_METADATA_DEFINITION_COUNT = 4 * 1_024 * 1_024
    const val MAX_SYMBOL_COUNT = MAX_METADATA_DEFINITION_COUNT * 3
    const val MAX_NAME_LENGTH = 1_024
    const val MAX_QUALIFIED_NAME_LENGTH = 4_096
    const val MAX_SEARCH_QUERY_LENGTH = 1_024
    const val MAX_PROCESS_NAME_LENGTH = 512
    const val MAX_ERROR_LENGTH = 512
    const val MAX_FIELD_READ_COUNT = 64
    const val MAX_DISPLAY_VALUE_LENGTH = 1_024
    const val MAX_INSTRUCTION_BYTES_LENGTH = 64
    const val MAX_INSTRUCTION_MNEMONIC_LENGTH = 64
    const val MAX_INSTRUCTION_OPERANDS_LENGTH = 1_024
    const val DEFAULT_REQUEST_TIMEOUT_MILLIS = 15_000L
    const val DEFAULT_CONNECTION_TIMEOUT_MILLIS = 30_000L
    const val OPEN_TARGET_REQUEST_TIMEOUT_MILLIS = 120_000L
    const val METHOD_ANALYSIS_REQUEST_TIMEOUT_MILLIS = 120_000L

    object Command {
        const val SCAN_PROCESSES = 100
        const val OPEN_TARGET = 101
        const val LIST_ASSEMBLIES = 102
        const val LIST_NAMESPACES = 103
        const val LIST_CLASSES = 104
        const val CLASS_MEMBERS = 105
        const val READ_VISIBLE_FIELDS = 106
        const val WRITE_PRIMITIVE = 107
        const val CLOSE_TARGET = 108
        const val CLASS_INFO = 109
        const val SEARCH_TYPES = 110
        const val SEARCH_SYMBOLS = 111
        const val METHOD_ANALYSIS = 112

        private val values = setOf(
            SCAN_PROCESSES,
            OPEN_TARGET,
            LIST_ASSEMBLIES,
            LIST_NAMESPACES,
            LIST_CLASSES,
            CLASS_MEMBERS,
            READ_VISIBLE_FIELDS,
            WRITE_PRIMITIVE,
            CLOSE_TARGET,
            CLASS_INFO,
            SEARCH_TYPES,
            SEARCH_SYMBOLS,
            METHOD_ANALYSIS,
        )

        fun isKnown(value: Int): Boolean = value in values
    }

    object Status {
        const val SUCCESS = 0
        const val ERROR = 1

        fun isKnown(value: Int): Boolean = value == SUCCESS || value == ERROR
    }

    object Error {
        const val NONE = 0
        const val MALFORMED_REQUEST = 1
        const val UNSUPPORTED_PROTOCOL = 2
        const val UNKNOWN_COMMAND = 3
        const val INVALID_ARGUMENT = 4
        const val NATIVE_UNAVAILABLE = 5
        const val PROCESS_NOT_FOUND = 6
        const val PROCESS_CHANGED = 7
        const val NOT_IL2CPP = 8
        const val METADATA_NOT_FOUND = 9
        const val METADATA_UNSUPPORTED = 10
        const val NO_TARGET = 11
        const val OUT_OF_RANGE = 12
        const val UNRESOLVED_METADATA = 13
        const val UNSUPPORTED_TYPE = 14
        const val MEMORY_ACCESS_DENIED = 15
        const val NATIVE_FAILURE = 16
        const val INTERNAL = 17
        const val SERVICE_DISCONNECTED = 18
        const val TIMEOUT = 19
    }

    object Key {
        const val VERSION = "protocol_version"
        const val REQUEST_ID = "request_id"
        const val COMMAND = "command"
        const val STATUS = "status"
        const val ERROR_CODE = "error_code"
        const val ERROR_MESSAGE = "error_message"
        const val OFFSET = "offset"
        const val LIMIT = "limit"
        const val TOTAL_COUNT = "total_count"
        const val PID = "pid"
        const val PIDS = "pids"
        const val PROCESS_NAME = "process_name"
        const val PROCESS_NAMES = "process_names"
        const val START_TICKS = "start_ticks"
        const val START_TICKS_LIST = "start_ticks_list"
        const val ASSEMBLY_INDEX = "assembly_index"
        const val NAMESPACE_INDEX = "namespace_index"
        const val CLASS_INDEX = "class_index"
        const val CLASS_NAME = "class_name"
        const val CLASS_NAMESPACE_NAME = "class_namespace_name"
        const val CLASS_ASSEMBLY_INDEX = "class_assembly_index"
        const val CLASS_ASSEMBLY_NAME = "class_assembly_name"
        const val MEMBER_KIND = "member_kind"
        const val METHOD_INDEX = "method_index"
        const val ANALYSIS_SECTION = "analysis_section"
        const val ANALYSIS_STATUS = "analysis_status"
        const val INDIRECT_CALL_COUNT = "indirect_call_count"
        const val INDICES = "indices"
        const val NAMES = "names"
        const val TYPE_NAMES = "type_names"
        const val TYPE_RESOLVED = "type_resolved"
        const val OFFSETS = "offsets"
        const val FLAGS = "flags"
        const val SIGNATURES = "signatures"
        const val SIGNATURE_RESOLVED = "signature_resolved"
        const val ADDRESSES = "addresses"
        const val RVAS = "rvas"
        const val OBJECT_ADDRESS = "object_address"
        const val FIELD_INDICES = "field_indices"
        const val FIELD_INDEX = "field_index"
        const val READ_STATUSES = "read_statuses"
        const val VALUE_KINDS = "value_kinds"
        const val DISPLAY_VALUES = "display_values"
        const val VALUE_KIND = "value_kind"
        const val BOOLEAN_VALUE = "boolean_value"
        const val INT_VALUE = "int_value"
        const val LONG_VALUE = "long_value"
        const val FLOAT_VALUE = "float_value"
        const val DOUBLE_VALUE = "double_value"
        const val BYTES_WRITTEN = "bytes_written"
        const val CLASS_FLAGS = "class_flags"
        const val CLASS_TOKEN = "class_token"
        const val CLASS_BITFIELD = "class_bitfield"
        const val PARENT_TYPE_PRESENT = "parent_type_present"
        const val PARENT_TYPE_INDEX = "parent_type_index"
        const val PARENT_TYPE_NAME = "parent_type_name"
        const val PARENT_TYPE_NAME_RESOLVED = "parent_type_name_resolved"
        const val PARENT_DEFINITION_INDEX = "parent_definition_index"
        const val PARENT_DEFINITION_INDEX_PRESENT = "parent_definition_index_present"
        const val DECLARING_TYPE_PRESENT = "declaring_type_present"
        const val DECLARING_TYPE_INDEX = "declaring_type_index"
        const val DECLARING_TYPE_NAME = "declaring_type_name"
        const val DECLARING_TYPE_NAME_RESOLVED = "declaring_type_name_resolved"
        const val DECLARING_DEFINITION_INDEX = "declaring_definition_index"
        const val DECLARING_DEFINITION_INDEX_PRESENT = "declaring_definition_index_present"
        const val TYPE_SIZES = "type_sizes"
        const val TYPE_SIZES_RESOLVED = "type_sizes_resolved"
        const val TYPE_INDICES = "type_indices"
        const val DEFINITION_INDICES = "definition_indices"
        const val DEFINITION_INDEX_RESOLVED = "definition_index_resolved"
        const val TYPE_INDEX_PRESENT = "type_index_present"
        const val OFFSET_RESOLVED = "offset_resolved"
        const val FLAGS_RESOLVED = "flags_resolved"
        const val TOKENS = "tokens"
        const val ADDRESS_RESOLVED = "address_resolved"
        const val RVA_RESOLVED = "rva_resolved"
        const val GETTER_FLAGS = "getter_flags"
        const val GETTER_PRESENT = "getter_present"
        const val SETTER_FLAGS = "setter_flags"
        const val SETTER_PRESENT = "setter_present"
        const val ATTRIBUTES = "attributes"
        const val ADD_FLAGS = "add_flags"
        const val ADD_PRESENT = "add_present"
        const val REMOVE_FLAGS = "remove_flags"
        const val REMOVE_PRESENT = "remove_present"
        const val RAISE_FLAGS = "raise_flags"
        const val RAISE_PRESENT = "raise_present"
        const val QUERY = "query"
        const val MATCH_MODE = "match_mode"
        const val MATCH_CASE = "match_case"
        const val QUALIFIED_NAMES = "qualified_names"
        const val SYMBOL_KINDS = "symbol_kinds"
        const val CLASS_INDICES = "class_indices"
        const val MEMBER_INDICES = "member_indices"
        const val ASSEMBLY_NAMES = "assembly_names"
        const val OWNER_NAMES = "owner_names"
        const val INSTRUCTION_BYTES = "instruction_bytes"
        const val MNEMONICS = "mnemonics"
        const val OPERANDS = "operands"
        const val REFERENCE_RESOLVED = "reference_resolved"
        const val CALL_SITE_ADDRESSES = "call_site_addresses"
        const val CALL_SITE_RVAS = "call_site_rvas"
        const val CALL_SITE_RVA_RESOLVED = "call_site_rva_resolved"
        const val CALL_SITE_INSTRUCTION_INDICES = "call_site_instruction_indices"
        const val FLOW_KINDS = "flow_kinds"
        const val TARGET_INSTRUCTION_INDICES = "target_instruction_indices"
        const val TARGET_PRESENT = "target_present"
        const val TARGET_METHOD_RESOLVED = "target_method_resolved"
        const val TARGET_SIGNATURE_RESOLVED = "target_signature_resolved"
        const val TARGET_CLASS_INDICES = "target_class_indices"
        const val TARGET_METHOD_INDICES = "target_method_indices"
        const val TARGET_NAMES = "target_names"
        const val TARGET_OWNER_NAMES = "target_owner_names"
        const val TARGET_SIGNATURES = "target_signatures"
        const val TARGET_ADDRESSES = "target_addresses"
        const val TARGET_RVAS = "target_rvas"
        const val TARGET_RVA_RESOLVED = "target_rva_resolved"
    }
}

class ProtocolException(
    val errorCode: Int,
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

class RemoteServiceException(
    val errorCode: Int,
    message: String,
) : IllegalStateException(message)
