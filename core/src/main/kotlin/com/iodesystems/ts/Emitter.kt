package com.iodesystems.ts

import com.iodesystems.ts.extractor.JvmExtractor
import com.iodesystems.ts.model.ApiMethod
import com.iodesystems.ts.model.ApiModel
import com.iodesystems.ts.model.TsRef
import com.iodesystems.ts.model.TsType
import java.io.File

class Emitter(
    private val config: Config,
    val extraction: JvmExtractor.Extraction
) {

    companion object Companion {
        fun lib(): String = """
        export type RequestInterceptor = (input: RequestInfo, init: RequestInit) => Promise<[RequestInfo, RequestInit]> | [RequestInfo, RequestInit]
        export type ResponseInterceptor = (response: Promise<Response>) => Promise<Response>
        export type ApiOptions = {
          baseUrl?: string
          requestInterceptor?: RequestInterceptor
          responseInterceptor?: ResponseInterceptor
          fetchImpl?: typeof fetch
        }

        export type AbortablePromise<T> = (() => void) & {
          abort: () => void;
          then<TResult1 = T, TResult2 = never>(
            onfulfilled?: ((value: T) => TResult1 | PromiseLike<TResult1>) | undefined | null,
            onrejected?: ((reason: any) => TResult2 | PromiseLike<TResult2>) | undefined | null
          ): AbortablePromise<TResult1 | TResult2>;
          catch<TResult = never>(
            onrejected?: ((reason: any) => TResult | PromiseLike<TResult>) | undefined | null
          ): AbortablePromise<T | TResult>;
          finally(onfinally?: (() => void) | undefined | null): AbortablePromise<T>;
        } & Promise<T>

        export function abortable<T>(
          promise: Promise<T>,
          controller: AbortController,
          onAbort: () => void
        ): AbortablePromise<T> {
          const abort = () => {
            onAbort()
            controller.abort("Request cancelled")
          }

          const abortablePromise = abort as AbortablePromise<T>
          abortablePromise.abort = abort

          // Override then/catch/finally to return AbortablePromise
          const originalThen = promise.then.bind(promise)
          abortablePromise.then = function<TResult1 = T, TResult2 = never>(
            onfulfilled?: ((value: T) => TResult1 | PromiseLike<TResult1>) | undefined | null,
            onrejected?: ((reason: any) => TResult2 | PromiseLike<TResult2>) | undefined | null
          ): AbortablePromise<TResult1 | TResult2> {
            return abortable(originalThen(onfulfilled, onrejected), controller, onAbort)
          } as any

          const originalCatch = promise.catch.bind(promise)
          abortablePromise.catch = function<TResult = never>(
            onrejected?: ((reason: any) => TResult | PromiseLike<TResult>) | undefined | null
          ): AbortablePromise<T | TResult> {
            return abortable(originalCatch(onrejected), controller, onAbort)
          } as any

          const originalFinally = promise.finally.bind(promise)
          abortablePromise.finally = function(
            onfinally?: (() => void) | undefined | null
          ): AbortablePromise<T> {
            return abortable(originalFinally(onfinally), controller, onAbort)
          } as any

          return abortablePromise
        }

        export function flattenQueryParams(path: string, params?: any, prefix: string|null = null): string {
          if (params == null) return path
          const out = new URLSearchParams()
          const appendVal = (k: string, v: any) => { if (v === undefined || v === null) return; out.append(k, String(v)) }
          const walk = (pfx: string, val: any) => {
            if (val === null || val === undefined) return
            if (Array.isArray(val)) {
              for (let i = 0; i < val.length; i++) { walk(pfx + "[" + i + "]", val[i]) }
            } else if (typeof val === 'object' && !(val instanceof Date) && !(val instanceof Blob)) {
              for (const k of Object.keys(val)) {
                const next = pfx ? (pfx + "." + k) : k
                walk(next, (val as any)[k])
              }
            } else {
              appendVal(pfx, val)
            }
          }
          if (prefix) {
            walk(prefix, params)
          } else {
            for (const k of Object.keys(params)) walk(k, (params as any)[k])
          }
          const qs = out.toString()
          return qs ? (path + "?" + qs) : path
        }
        
        export function fetchInternal(opts: ApiOptions, path: string, init: RequestInit): AbortablePromise<Response> {
          const controller = new AbortController()
          const baseUrl = opts.baseUrl ?? ""
          let input: RequestInfo = baseUrl + path
          let options: RequestInit = { ...init, signal: controller.signal }

          const performFetch = async (): Promise<Response> => {
            if (opts.requestInterceptor) {
              const out = await opts.requestInterceptor(input, options)
              input = out[0]; options = out[1]
            }
            const f = opts.fetchImpl ?? fetch
            const res = f(input, options)
            if (opts.responseInterceptor) {
              return opts.responseInterceptor(res)
            } else {
              return res
            }
          }

          return abortable(performFetch(), controller, () => {})
        }
        """.trimIndent()

        /** Pure TypeScript hook file - no JSX */
        fun reactHook(libImportPath: String): String = """
        import { createContext, useContext, useMemo } from 'react'
        import type { ApiOptions } from '$libImportPath'

        // Re-export ApiOptions so provider can import from this file
        export type { ApiOptions }

        // Type for API class constructors
        export type ApiType<T> = new (opts: ApiOptions) => T

        // Context for API options - exported so provider can use it
        export const ApiContext = createContext<ApiOptions>({})

        // Bind all methods of an object to itself
        function bind<T extends object>(obj: T): T {
          const proto = Object.getPrototypeOf(obj)
          for (const key of Object.getOwnPropertyNames(proto)) {
            const val = (obj as any)[key]
            if (typeof val === 'function' && key !== 'constructor') {
              (obj as any)[key] = val.bind(obj)
            }
          }
          return obj
        }

        /**
         * React hook to get a typed API client instance.
         * The instance is cached for the component's lifecycle and recreated when ApiOptions change.
         *
         * @example
         * const api = useApi(MyApi)
         * api.someMethod({ ... })
         */
        export function useApi<T extends object>(ctor: ApiType<T>): T {
          const apiOptions = useContext(ApiContext)
          const cache = useMemo(() => new Map<string, unknown>(), [apiOptions])
          const existing = cache.get(ctor.name)
          if (existing) return existing as T
          const api = bind(new ctor(apiOptions))
          cache.set(ctor.name, api)
          return api
        }
        """.trimIndent()

        /** TSX provider component file - requires JSX */
        fun reactProvider(hookImportPath: String): String = """
        import { ReactNode, useMemo } from 'react'
        import { ApiContext, ApiOptions } from '$hookImportPath'

        export interface ApiProviderProps {
          children: ReactNode
          options?: ApiOptions
        }

        /**
         * Provider component that supplies API options to all child components.
         * Wrap your app or a subtree with this to configure API behavior.
         *
         * @example
         * const apiOptions = useMemo(() => ({ baseUrl: '/api' }), [])
         * <ApiProvider options={apiOptions}>
         *   <App />
         * </ApiProvider>
         */
        export function ApiProvider({ children, options }: ApiProviderProps) {
          const value = useMemo(() => options ?? {}, [options])
          return <ApiContext.Provider value={value}>{children}</ApiContext.Provider>
        }
        """.trimIndent()

        fun fieldName(name: String): String {
            if (name.isEmpty()) error("Name must not be empty")
            val first = name[0]
            val needsQuotes = first.isDigit() ||
                    !first.isLetterOrDigit() && first != '_' && first != '$' ||
                    name.any { !it.isLetterOrDigit() && it != '_' && it != '$' } ||
                    name in setOf(
                "break", "case", "catch", "class", "const", "continue",
                "debugger", "default", "delete", "do", "else", "enum",
                "export", "extends", "false", "finally", "for", "function",
                "if", "import", "in", "instanceof", "new", "null", "return",
                "super", "switch", "this", "throw", "true", "try", "typeof",
                "var", "void", "while", "with", "as", "implements",
                "interface", "let", "package", "private", "protected",
                "public", "static", "yield"
            )
            return if (needsQuotes) "\"$name\"" else name
        }

        fun tsNameWithGenericsResolved(type: TsType, emptyGenericStubs: Set<String> = emptySet()): String {
            val name = type.name
            val generics = type.generics
            if (generics.isEmpty()) return name

            val lt = name.indexOf('<')
            val gt = name.lastIndexOf('>')

            // Handle mapped types like "T | null" where generics appear outside angle brackets
            if (lt == -1 || gt == -1 || gt < lt) {
                // No angle brackets - substitute generics directly in the name
                var result = name
                for ((paramName, paramType) in generics) {
                    val resolved = tsNameWithGenericsResolved(paramType, emptyGenericStubs)
                    val withNullability = if (paramType.nullable) "$resolved | null" else resolved
                    // Replace the type parameter name with its resolved type
                    // Use word boundary matching to avoid replacing partial matches
                    result = result.replace(Regex("\\b${Regex.escape(paramName)}\\b"), withNullability)
                }
                return result
            }

            val base = name.take(lt)
            // Empty generic stubs have no fields/intersections — generics are unused, drop them
            if (base in emptyGenericStubs) return base

            val genericSection = name.substring(lt + 1, gt)

            fun splitTopLevel(s: String): List<String> {
                val out = mutableListOf<String>()
                val sb = StringBuilder()
                var depth = 0
                for (ch in s) {
                    when (ch) {
                        '<' -> {
                            depth++
                            sb.append(ch)
                        }

                        '>' -> {
                            depth--
                            sb.append(ch)
                        }

                        ',' -> {
                            if (depth == 0) {
                                out.add(sb.toString())
                                sb.setLength(0)
                            } else sb.append(ch)
                        }

                        else -> sb.append(ch)
                    }
                }
                if (sb.isNotEmpty()) out.add(sb.toString())
                return out
            }

            val resolvedArgs = splitTopLevel(genericSection).map { rawArg ->
                val arg = rawArg.trim()
                val mapped = generics[arg]
                if (mapped != null) {
                    val resolved = tsNameWithGenericsResolved(mapped, emptyGenericStubs)
                    if (mapped.nullable) "$resolved | null" else resolved
                } else arg
            }

            return base + "<" + resolvedArgs.joinToString(",") + ">"
        }
    }

    class WriteContext(
        val file: File,
        val header: StringBuilder = StringBuilder(),
        val content: StringBuilder = StringBuilder(),
        val ownedTypes: MutableSet<String> = mutableSetOf(),
        val alreadyImported: MutableSet<String> = mutableSetOf(),
        val deferredImports: MutableList<Pair<String, String>> = mutableListOf(),
    ) {
        fun write(string: String) {
            content.append(string)
        }

        fun writeHeader(string: String) {
            header.append(string)
        }

        /** Queue an import line that will only be emitted if [name] appears in the final content. */
        fun deferImport(name: String, importLine: String) {
            deferredImports.add(name to importLine)
        }

        fun importFrom(other: WriteContext): String {
            return "./" + other.file.relativeTo(file.parentFile)
        }

        fun resolvedContent(): String {
            val sb = StringBuilder()
            sb.append(header)
            for ((name, importLine) in deferredImports) {
                if (name in content) {
                    sb.append(importLine)
                    if (!importLine.endsWith("\n")) sb.append("\n")
                }
            }
            sb.append(content)
            return sb.toString()
        }
    }

    data class Output(
        val files: List<WriteContext>
    ) {
        fun write() {
            files.forEach {
                it.file.parentFile.mkdirs()
                if (it.file.exists()) it.file.delete()
            }
            files.forEach { file ->
                file.file.writeText(file.resolvedContent())
            }
        }
    }


    fun ts(): Output {
        val outputDir = File(config.outputDirectory.ifBlank { "./" })
        val apiFileMapping = config.groupApiFile
        val typesFile = config.typesFileName ?: if (apiFileMapping != null) "api-types.ts"
        else null
        val libFile = config.emitLibFileName ?: if (typesFile != null) "api-lib.ts"
        else "api.ts"

        val writeContexts = mutableMapOf<String, WriteContext>()
        val typeLocations = mutableMapOf<String, WriteContext>()
        val regexCache = mutableMapOf<String, Regex>()

        val isLibExternal = libFile == "api-lib.ts"

        fun createWriteContext(file: String): WriteContext {
            return writeContexts.getOrPut(file) {
                val ctx = WriteContext(outputDir.resolve(file))
                // Write configured header lines at the top of every generated file
                config.headerLines.forEach { line ->
                    ctx.writeHeader(line)
                    if (!line.endsWith("\n")) ctx.writeHeader("\n")
                }
                if (!isLibExternal || file != libFile) {
                    config.externalImportLines.forEach { (name, line) ->
                        ctx.deferImport(name, line)
                    }
                }
                ctx
            }
        }

        fun ApiModel.writeContext(): WriteContext {
            val file = apiFileMapping?.firstNotNullOfOrNull { (file, matches) ->
                if (matches.any { it == jvmQualifiedClassName }) file
                else if (matches.any { regexCache.getOrPut(it) { Regex(it) }.matches(jvmQualifiedClassName) }) file
                else null
            } ?: "api.ts"
            return createWriteContext(file)
        }

        fun writeContextForType(): WriteContext {
            val file = typesFile ?: "api.ts"
            return createWriteContext(file)
        }

        val lib = createWriteContext(libFile)
        lib.write(lib())
        lib.write("\n")

        // Identify empty generic stub types: Object types with generics but no fields and no intersections.
        // Their generic params are unused, so we strip them from declarations and references.
        val emptyGenericStubs = extraction.types.asSequence()
            .filterIsInstance<TsType.Object>()
            .filter { '<' in it.name && it.fields.isEmpty() && it.intersections.isEmpty() }
            .map { it.name.substringBefore('<') }
            .toSet()

        fun resolveType(type: TsType) = tsNameWithGenericsResolved(type, emptyGenericStubs)
        fun stripGenerics(name: String): String {
            val lt = name.indexOf('<')
            return if (lt != -1) name.substring(0, lt) else name
        }

        extraction.types.sortedBy { it.name }.forEach { type ->
            val o = writeContextForType()
            o.ownedTypes.add(type.name)
            typeLocations[type.name] = o

            o.write("/**\n")
            o.write(" * Jvm {@link ${type.fqcn}}\n")
            if (config.includeRefComments) {
                extraction.typeReferences.filter { it.toTsBaseName == type.name }
                    .groupBy { it.refType }
                    .forEach { (type, refs) ->
                        o.write(" * $type ref:\n")
                        refs.forEach { ref ->
                            o.write(" * - {@link ${ref.fromTsBaseName}}\n")
                        }
                    }
            }
            o.write(" */\n")

            val declName = if (stripGenerics(type.name) in emptyGenericStubs) stripGenerics(type.name) else type.name
            o.write("export type $declName = ")
            when (type) {
                is TsType.Inline -> error("Inline types are NOT exported. This is an error here")
                is TsType.Object -> {

                    val supers = type.intersections
                    // Alias-like object: no fields, exactly one supertype, no discriminator
                    if (type.fields.isEmpty() && supers.size == 1 && type.discriminator == null) {
                        o.write(resolveType(supers.first()))
                        o.write("\n")
                        return@forEach
                    }
                    if (supers.isNotEmpty()) {
                        o.write(supers.joinToString(" & ") { resolveType(it) })
                        o.write(" & ")
                    }
                    o.write("{\n")
                    val discriminatorKey = type.discriminator?.first
                    type.discriminator?.let { (key, value) ->
                        o.write("  \"${key}\": \"$value\"\n")
                    }
                    type.fields.entries.sortedBy { it.key }.forEach { (field, f) ->
                        if (discriminatorKey != null && field == discriminatorKey) return@forEach
                        o.write("  ${fieldName(field)}")
                        if (f.optional) {
                            o.write("?")
                        }
                        o.write(": ")
                        val fieldType = resolveType(f.type)
                        o.write(fieldType)
                        // Only add | null if the type doesn't already end with | null (avoids duplication)
                        if (f.nullable && !fieldType.endsWith("| null")) {
                            o.write(" | null")
                        }
                        if (f.optional) {
                            o.write(" | undefined")
                        }
                        o.write("\n")
                    }
                    o.write("}\n")
                }

                is TsType.Union -> {
                    val unionBody = type.children.sortedBy { it.name }.joinToString(" | ") { child ->
                        resolveType(child)
                    }
                    if (type.supertypes.isNotEmpty()) {
                        val supers = type.supertypes.joinToString(" & ") { resolveType(it) }
                        o.write("$supers & ($unionBody)\n")
                    } else {
                        o.write("$unionBody\n")
                    }
                }

                is TsType.Enum -> {
                    o.write(type.unionLiteral)
                    o.write("\n")
                }

                is TsType.Alias -> {
                    o.write(type.aliasTo)
                    o.write("\n")
                }
            }
        }

        val didImportTypes = mutableSetOf<WriteContext>()
        extraction.apis.forEach { api ->
            val o = api.writeContext()

            if (o != lib) {
                if (didImportTypes.add(o)) {
                    val libPath = o.importFrom(lib).dropLast(3)
                    o.write("import { AbortablePromise, ApiOptions, fetchInternal } from '$libPath'\n")
                    o.deferImport("flattenQueryParams", "import { flattenQueryParams } from '$libPath'")
                }
            }


            val importTypes = mutableSetOf<String>()
            extraction.typeReferences.forEach { ref ->
                if (ref.refType != TsRef.Type.METHOD) return@forEach
                if (!ref.fromTsBaseName.startsWith(api.tsBaseName + "#")) return@forEach
                importTypes.add(ref.toTsBaseName)
            }

            if (importTypes.isNotEmpty()) {
                val ctx = writeContextForType()
                if (ctx != o) {
                    val path = o.importFrom(ctx).dropLast(3)
                    // Strip generic type parameters from import names (e.g., "DataSetResponse<T>" -> "DataSetResponse")
                    val names = importTypes.map { name ->
                        val ltIndex = name.indexOf('<')
                        if (ltIndex != -1) name.substring(0, ltIndex) else name
                    }.filter {
                        !o.alreadyImported.contains(it)
                    }.toSet().toList().sorted()
                    o.alreadyImported.addAll(names)
                    if (names.isNotEmpty()) {
                        o.write("import { ${names.joinToString(", ")} } from '$path'\n")
                    }
                }
            }

            // Write JSDoc comment with JVM reference
            if (config.includeRefComments) {
                o.write("/**\n")
                o.write(" * Jvm {@link ${api.jvmQualifiedClassName}}\n")
                o.write(" */\n")
            }
            o.write("export class ${api.tsBaseName} {\n")
            o.write("  constructor(private opts: ApiOptions = {}) {}\n")
            api.apiMethods.forEach { method ->
                val req = method.requestBodyType
                val res = method.responseBodyType

                fun withNullability(name: String, t: TsType?): String {
                    if (t == null) return name
                    val nullable = if (t.nullable) " | null" else ""
                    val optional = if (t.optional) " | undefined" else ""
                    return name + nullable + optional
                }

                // Build TypeScript method signature: path, query, req
                val sigParts = mutableListOf<String>()
                if (method.pathReplacements.isNotEmpty()) {
                    val fields = method.pathReplacements.joinToString(", ") { p ->
                        val tsType = when (p.type) {
                            ApiMethod.PathParam.Type.STRING -> "string"
                            ApiMethod.PathParam.Type.NUMBER -> "string | number"
                        }
                        "${p.name}: $tsType"
                    }
                    sigParts += "path: { $fields }"
                }
                if (method.queryParamsType != null) {
                    val qn = resolveType(method.queryParamsType)
                    sigParts += "query: $qn"
                }
                if (req != null) {
                    val bodyName = resolveType(req)
                    sigParts += (if (req.optional) "req?: " else "req: ") + withNullability(bodyName, req)
                }

                val sig = sigParts.joinToString(", ")
                val resName = withNullability(resolveType(res), res)
                o.write("  ${method.name}($sig): AbortablePromise<${resName}> {\n")
                o.write("    return fetchInternal(this.opts, ")
                if (method.queryParamsType != null) {
                    o.write("flattenQueryParams(")
                }
                o.write("\"${method.path}\"")
                method.pathReplacements.forEach { p ->
                    o.write(".replace(\"{${p.placeholder}}\", String(path.${p.name}))")
                }
                if (method.queryParamsType != null) {
                    o.write(", query, null)")
                }
                o.write(", {\n")
                o.write("      method: \"${method.httpMethod.name}\"")
                if (req != null) {
                    o.write(",\n")
                    o.write("      headers: {'Content-Type': 'application/json'},\n")
                    o.write("      body: JSON.stringify(req)\n")
                } else {
                    o.write("\n")
                }
                o.write("    })")
                if (res.name == "void") {
                    o.write(".then(()=>{})\n")
                } else {
                    o.write(".then(r=>r.json())\n")
                }
                o.write("  }\n")
            }
            o.write("}\n")
        }

        // Emit React helper files if enabled
        if (config.emitReactHelpers) {
            // Emit hook file (.ts - pure TypeScript, no JSX)
            val hookFile = createWriteContext(config.reactHookFileName)
            val libImportPath = "./" + lib.file.nameWithoutExtension
            hookFile.write(reactHook(libImportPath))
            hookFile.write("\n")

            // Emit provider file (.tsx - requires JSX)
            val providerFile = createWriteContext(config.reactProviderFileName)
            val hookImportPath = "./" + hookFile.file.nameWithoutExtension
            providerFile.write(reactProvider(hookImportPath))
            providerFile.write("\n")
        }

        return Output(writeContexts.values.toList())
    }


}