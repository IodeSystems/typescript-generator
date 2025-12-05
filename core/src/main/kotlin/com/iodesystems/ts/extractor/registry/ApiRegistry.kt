package com.iodesystems.ts.extractor.registry

import com.iodesystems.ts.model.TsHttpMethod

// High-level registry that describes APIs and methods without resolving JVM types.
// Frameworks (e.g., Spring) should populate this and JvmExtractor will resolve types.
class ApiRegistry private constructor(val apis: List<ApiDescriptor>) {
    class Builder {
        private val list = mutableListOf<ApiDescriptor>()
        fun api(controllerFqn: String, block: ApiDescriptor.Builder.() -> Unit) {
            list += ApiDescriptor.Builder(controllerFqn).apply(block).build()
        }

        fun api(controller: Class<*>, block: ApiDescriptor.Builder.() -> Unit) =
            api(controller.name, block)

        fun build() = ApiRegistry(list)
    }

    companion object {
        fun build(block: Builder.() -> Unit) = Builder().apply(block).build()
    }
}

class ApiDescriptor(
    val controllerFqn: String,
    val basePath: String?,
    val methods: List<ApiMethodDescriptor>
) {
    class Builder(private val controllerFqn: String) {
        private var basePath: String? = null
        private val methods = mutableListOf<ApiMethodDescriptor>()

        fun basePath(path: String) {
            this.basePath = path
        }

        fun method(name: String, block: ApiMethodDescriptor.Builder.() -> Unit) {
            methods += ApiMethodDescriptor.Builder(name).apply(block).build()
        }

        fun build() = ApiDescriptor(controllerFqn, basePath, methods)
    }
}

class ApiMethodDescriptor(
    val name: String,
    val http: TsHttpMethod,
    val path: String,
    val bodyIndex: Int?,
    val params: List<ParamDescriptor>
) {
    class Builder(private val name: String) {
        private var http: TsHttpMethod = TsHttpMethod.GET
        private var path: String = "/"
        private var bodyIndex: Int? = null
        private val params = mutableListOf<ParamDescriptor>()

        fun http(method: TsHttpMethod) {
            this.http = method
        }

        fun path(path: String) {
            this.path = path
        }

        fun body(index: Int) {
            this.bodyIndex = index
        }

        fun queryParam(index: Int, name: String, optional: Boolean = false) {
            params += ParamDescriptor(index, name, optional, ParamKind.QUERY, null)
        }

        fun pathParam(index: Int, placeholder: String, name: String, optional: Boolean = false) {
            params += ParamDescriptor(index, name, optional, ParamKind.PATH, placeholder)
        }

        fun build() = ApiMethodDescriptor(name, http, path, bodyIndex, params)
    }
}

enum class ParamKind { QUERY, PATH }

data class ParamDescriptor(
    val index: Int,
    val name: String,
    val optional: Boolean,
    val kind: ParamKind,
    // For PATH kind: placeholder name inside {placeholder} in the path template; null for QUERY
    val placeholder: String?
)

