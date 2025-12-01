package com.iodesystems.ts.model

// References for documentation/traceability
sealed interface TsRef {
    data class ByType(val jvmQualifiedClassName: String) : TsRef
    data class ByMethod(val controllerJvmQualifiedMethodName: String) : TsRef
}
