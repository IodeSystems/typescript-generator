package com.iodesystems.ts


import com.iodesystems.ts.model.ExtractionResult
import com.iodesystems.ts.model.TsBody
import com.iodesystems.ts.model.TsType
import com.iodesystems.ts.model.TsRef

class Emitter(private val config: Config) {

    private fun quotePropIfNeeded(name: String): String {
        // Valid TypeScript identifier or property name without quoting
        // Allow '@' in property names to match project expectations (e.g., @type)
        val ident = Regex("^[A-Za-z_@$][A-Za-z0-9_@$]*$")
        return if (ident.matches(name)) name else "\"" + name.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }

    fun emitLib(): String = """
        export type RequestInterceptor = (input: RequestInfo, init: RequestInit) => Promise<[RequestInfo, RequestInit]> | [RequestInfo, RequestInit]
        export type ResponseInterceptor = (response: Promise<Response>) => Promise<Response>
        export type ApiOptions = {
          baseUrl?: string
          requestInterceptor?: RequestInterceptor
          responseInterceptor?: ResponseInterceptor
          fetchImpl?: typeof fetch
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
        
        export async function fetchInternal(opts: ApiOptions, path: string, init: RequestInit): Promise<Response> {
          const baseUrl = opts.baseUrl ?? ""
          let input: RequestInfo = baseUrl + path
          let options: RequestInit = init
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
        """.trimIndent()

    // New API: emit types for a single output module. typeSource maps alias -> module path (relative, extensionless).
    fun emitTypes(types: List<TsType>, typeSource: Map<String, String>? = null): String {
        if (types.isEmpty()) return ""
        val inFileAliases = types.map { baseAliasName(it.typeScriptTypeName) }.toSet()
        val currentModule = typeSource?.let { ts ->
            val mods = types.mapNotNull { t -> ts[baseAliasName(t.typeScriptTypeName)] }.toSet()
            if (mods.size > 1) throw IllegalStateException("All provided types must target the same module. Found: $mods")
            mods.firstOrNull()
        }
        val tsLines = mutableListOf<String>()
        // Collect cross-file imports (aliases referenced by these types but mapped to other modules)
        if (typeSource != null) {
            val importsByModule = linkedMapOf<String, MutableSet<String>>()
            val externalImports = linkedMapOf<String, MutableSet<String>>()
            val explicitImportLines = linkedSetOf<String>()
            fun noteImport(alias: String) {
                val mod = typeSource[alias] ?: return
                if (currentModule != null && mod == currentModule) return
                importsByModule.getOrPut(mod) { linkedSetOf() } += alias
                // If alias is mapped to an external module as well, that takes precedence for import location
                val explicit = config.externalImportLines[alias]
                if (explicit != null) {
                    explicitImportLines += explicit
                    importsByModule[mod]?.remove(alias)
                } else {
                    config.externalImportType[alias]?.let { extMod ->
                        externalImports.getOrPut(extMod) { linkedSetOf() } += alias
                        // And remove from local module import set if present
                        importsByModule[mod]?.remove(alias)
                    }
                }
            }
            fun walkRefs(t: TsType?) {
                when (val b = t?.body) {
                    is TsBody.PrimitiveBody -> {
                        val a = baseAliasName(b.tsName)
                        if (!inFileAliases.contains(a)) {
                            // Either external or cross-file
                            val explicit = config.externalImportLines[a]
                            if (explicit != null) {
                                explicitImportLines += explicit
                            } else {
                                val ext = config.externalImportType[a]
                                if (ext != null) externalImports.getOrPut(ext) { linkedSetOf() } += a else noteImport(a)
                            }
                        }
                    }
                    is TsBody.ArrayBody -> walkRefs(b.element)
                    is TsBody.ObjectBody -> {
                        val a = baseAliasName(t.typeScriptTypeName)
                        if (!inFileAliases.contains(a)) {
                            val explicit = config.externalImportLines[a]
                            if (explicit != null) {
                                explicitImportLines += explicit
                            } else {
                                val ext = config.externalImportType[a]
                                if (ext != null) externalImports.getOrPut(ext) { linkedSetOf() } += a else noteImport(a)
                            }
                        }
                        b.tsFields.forEach { f -> walkRefs(f.type) }
                    }
                    is TsBody.UnionBody -> b.options.forEach { opt ->
                        val a = baseAliasName(opt.typeScriptTypeName)
                        if (!inFileAliases.contains(a)) {
                            val explicit = config.externalImportLines[a]
                            if (explicit != null) {
                                explicitImportLines += explicit
                            } else {
                                val ext = config.externalImportType[a]
                                if (ext != null) externalImports.getOrPut(ext) { linkedSetOf() } += a else noteImport(a)
                            }
                        }
                        walkRefs(opt)
                    }
                    null -> {}
                }
            }
            types.forEach { walkRefs(it) }
            // Emit import headers
            explicitImportLines.forEach { line -> tsLines += line }
            externalImports.forEach { (mod, names) ->
                tsLines += "import type { ${names.joinToString(", ")} } from '$mod'"
            }
            importsByModule.forEach { (mod, names) ->
                tsLines += "import type { ${names.joinToString(", ")} } from './$mod'"
            }
            if (importsByModule.isNotEmpty()) tsLines += ""
        }

        val emitted = HashSet<String>()
        // Only allow emitting types that belong to this file (if typeSource provided). Others must be imported.
        fun canEmit(alias: String): Boolean {
            if (typeSource == null) return true
            val mod = typeSource[alias]
            return mod == currentModule
        }

        fun emitObjectAlias(t: TsType) {
            val alias = baseAliasName(t.typeScriptTypeName)
            if (!canEmit(alias)) return
            if (!emitted.add(alias)) return
            // Do not force emission of intersected/external aliases; only recurse for in-file ones
            val resolved = t
            appendDocWithReferences(tsLines, resolved)
            val gp = if (resolved.genericParameters.isNotEmpty()) "<" + resolved.genericParameters.joinToString(",") + ">" else ""
            tsLines += "export type $alias$gp = {"
            val obj = (resolved.body as TsBody.ObjectBody)
            obj.tsFields.forEach { f ->
                val opt = if (f.optional) "?" else ""
                val pname = quotePropIfNeeded(f.name)
                tsLines += "  ${pname}" + opt + ": " + renderTypeName(f.type)
            }
            if (resolved.intersects.isNotEmpty()) {
                val adjusted = resolved.intersects
                tsLines += "} & " + adjusted.joinToString(" & ") { it }
            } else {
                tsLines += "}"
            }
            tsLines += ""
        }

        fun emitUnionAlias(name: String, jvm: String, u: TsBody.UnionBody) {
            if (u.options.all { it.body is TsBody.PrimitiveBody }) return
            val alias = baseAliasName(name)
            if (!canEmit(alias)) return
            if (!emitted.add(alias)) return
            appendDocComment(tsLines, jvm)
            val rendered = u.options.joinToString(" | ") { opt -> baseAliasName(opt.typeScriptTypeName) }
            tsLines += "export type $alias = $rendered"
            tsLines += ""
        }

        // Emit objects then unions
        types.forEach { t ->
            when (t.body) {
                is TsBody.ObjectBody -> emitObjectAlias(t)
                else -> {}
            }
        }
        types.forEach { t ->
            val ub = t.body as? TsBody.UnionBody ?: return@forEach
            emitUnionAlias(t.typeScriptTypeName, t.jvmQualifiedClassName, ub)
        }
        return tsLines.joinToString("\n")
    }

    // New API: emit APIs for a single output module. When typeSource is null, inline types.
    fun emitApis(
        apis: List<com.iodesystems.ts.model.ApiModel>,
        extraction: com.iodesystems.ts.model.ExtractionResult,
        typeSource: Map<String, String>? = null,
    ): String {
        val tsLines = mutableListOf<String>()
        val selected = apis.filter { config.includeApi(it.jvmQualifiedClassName) }
        // Keep a single emitted set across all selected APIs to avoid duplicating type aliases when inlining
        val emitted = HashSet<String>()

        fun baseAliasName(name: String): String {
            // Strip generics, intersections and unions from alias name for declaration/lookup
            return name.substringBefore(" & ").substringBefore(" | ").substringBefore("<")
        }
        // Fast lookup for known complex types by TS alias
        val typesByAlias = extraction.types.associateBy { it.typeScriptTypeName }

        // If not inlining types, add import headers for lib and types
        if (typeSource != null) {
            // lib import
            val libName = config.emitLibFileName ?: "api-lib.ts"
            tsLines += "import { ApiOptions, fetchInternal, flattenQueryParams } from './${libName.removeSuffix(".ts")}'"
            // collect used aliases and group by module
            val importsByModule = linkedMapOf<String, MutableSet<String>>()
            val externalImports = linkedMapOf<String, MutableSet<String>>()
            val explicitImportLines = linkedSetOf<String>()
            fun baseAlias(name: String): String = name.substringBefore(" & ").substringBefore(" | ").substringBefore("<")
            fun noteAlias(alias: String) {
                val mod = typeSource[alias] ?: return
                // external types override module mapping
                val explicit = config.externalImportLines[alias]
                if (explicit != null) {
                    explicitImportLines += explicit
                } else {
                    val ext = config.externalImportType[alias]
                    if (ext != null) {
                        externalImports.getOrPut(ext) { linkedSetOf() } += alias
                    } else {
                        importsByModule.getOrPut(mod) { linkedSetOf() } += alias
                    }
                }
            }
            fun walk(t: TsType?) {
                when (val b = t?.body) {
                    is TsBody.PrimitiveBody -> {
                        val a = baseAlias(b.tsName)
                        val explicit = config.externalImportLines[a]
                        if (explicit != null) {
                            explicitImportLines += explicit
                        } else {
                            val ext = config.externalImportType[a]
                            if (ext != null) externalImports.getOrPut(ext) { linkedSetOf() } += a else noteAlias(a)
                        }
                    }
                    is TsBody.ArrayBody -> walk(b.element)
                    is TsBody.ObjectBody -> {
                        val a = baseAlias(t.typeScriptTypeName)
                        val explicit = config.externalImportLines[a]
                        if (explicit != null) {
                            explicitImportLines += explicit
                        } else {
                            val ext = config.externalImportType[a]
                            if (ext != null) externalImports.getOrPut(ext) { linkedSetOf() } += a else noteAlias(a)
                        }
                        b.tsFields.forEach { f -> walk(f.type) }
                    }
                    is TsBody.UnionBody -> b.options.forEach { opt ->
                        val a = baseAlias(opt.typeScriptTypeName)
                        val explicit = config.externalImportLines[a]
                        if (explicit != null) {
                            explicitImportLines += explicit
                        } else {
                            val ext = config.externalImportType[a]
                            if (ext != null) externalImports.getOrPut(ext) { linkedSetOf() } += a else noteAlias(a)
                        }
                        walk(opt)
                    }
                    null -> {}
                }
            }
            selected.forEach { api ->
                api.apiMethods.forEach { m ->
                    walk(m.requestBodyType); walk(m.queryParamsType); m.pathTsFields.values.forEach { f -> walk(f.type) }; walk(m.responseBodyType)
                }
            }
            explicitImportLines.forEach { line -> tsLines += line }
            externalImports.forEach { (mod, names) ->
                tsLines += "import type { ${names.joinToString(", ")} } from '$mod'"
            }
            importsByModule.forEach { (mod, names) ->
                tsLines += "import type { ${names.joinToString(", ")} } from './$mod'"
            }
            if (importsByModule.isNotEmpty()) tsLines += ""
        }
        else {
            // Inline mode: still need to import external types used by the APIs
            val externalImports = linkedMapOf<String, MutableSet<String>>()
            val explicitImportLines = linkedSetOf<String>()
            fun baseAlias(name: String): String = name.substringBefore(" & ").substringBefore(" | ").substringBefore("<")
            fun walk(t: TsType?) {
                when (val b = t?.body) {
                    is TsBody.PrimitiveBody -> {
                        val a = baseAlias(b.tsName)
                        val explicit = config.externalImportLines[a]
                        if (explicit != null) {
                            explicitImportLines += explicit
                        } else {
                            val ext = config.externalImportType[a]
                            if (ext != null) externalImports.getOrPut(ext) { linkedSetOf() } += a
                        }
                    }
                    is TsBody.ArrayBody -> walk(b.element)
                    is TsBody.ObjectBody -> {
                        val a = baseAlias(t.typeScriptTypeName)
                        val explicit = config.externalImportLines[a]
                        if (explicit != null) {
                            explicitImportLines += explicit
                        } else {
                            val ext = config.externalImportType[a]
                            if (ext != null) externalImports.getOrPut(ext) { linkedSetOf() } += a
                        }
                        b.tsFields.forEach { f -> walk(f.type) }
                    }
                    is TsBody.UnionBody -> b.options.forEach { opt ->
                        val a = baseAlias(opt.typeScriptTypeName)
                        val explicit = config.externalImportLines[a]
                        if (explicit != null) {
                            explicitImportLines += explicit
                        } else {
                            val ext = config.externalImportType[a]
                            if (ext != null) externalImports.getOrPut(ext) { linkedSetOf() } += a
                        }
                        walk(opt)
                    }
                    null -> {}
                }
            }
            selected.forEach { api ->
                api.apiMethods.forEach { m ->
                    walk(m.requestBodyType); walk(m.queryParamsType); m.pathTsFields.values.forEach { f -> walk(f.type) }; walk(m.responseBodyType)
                }
            }
            explicitImportLines.forEach { line -> tsLines += line }
            externalImports.forEach { (mod, names) ->
                tsLines += "import type { ${names.joinToString(", ")} } from '$mod'"
            }
            if (externalImports.isNotEmpty()) tsLines += ""
        }

        selected.forEach { api ->
            tsLines += "export class ${api.cls} {"
            tsLines += "  constructor(private opts: ApiOptions = {}) {}"
            api.apiMethods.forEach { m ->
                val req = m.requestBodyType
                val query = m.queryParamsType
                val pathFields = m.pathTsFields
                val res = m.responseBodyType
                val resTs = res?.let { renderTypeName(it) } ?: "void"

                // Build function signature in one pass: (req?, path?, query?)
                val argParts = mutableListOf<String>()
                if (req != null) argParts += "req: ${renderTypeName(req)}"
                if (pathFields.isNotEmpty()) {
                    val pathObj = pathFields.entries.joinToString(", ") { (placeholder, field) ->
                        val opt = if (field.optional) "?" else ""
                        "${field.name}${opt}: ${renderTypeName(field.type)}"
                    }
                    argParts += "path: { ${pathObj} }"
                }
                if (query != null) argParts += "query: ${renderTypeName(query)}"
                val sig = "(" + argParts.joinToString(", ") + ")"

                // Build path call part: apply replacements if path params exist
                val pathExpr = if (pathFields.isEmpty()) {
                    "\"${m.path}\""
                } else {
                    val rep = pathFields.entries.joinToString("") { (placeholder, field) ->
                        ".replace(\"{${placeholder}}\", String(path.${field.name}))"
                    }
                    "\"${m.path}\"${rep}"
                }

                val pathCallPart = if (query != null) {
                    "flattenQueryParams(${pathExpr}, query, null)"
                } else {
                    pathExpr
                }

                tsLines += "  ${m.name}${sig}: Promise<${resTs}> {"
                tsLines += "    return fetchInternal(this.opts, ${pathCallPart}, {"
                if (req != null) {
                    tsLines += "      method: \"${m.httpMethod.name}\"," // method
                    tsLines += "      headers: {'Content-Type': 'application/json'},"
                    tsLines += "      body: JSON.stringify(req)"
                } else {
                    tsLines += "      method: \"${m.httpMethod.name}\"" // method
                }
                if (resTs == "void") {
                    tsLines += "    }).then(r=>undefined as any)"
                } else {
                    tsLines += "    }).then(r=>r.json())"
                }
                tsLines += "  }"
            }
            tsLines += "}"
            tsLines += ""

            if (typeSource == null) {
                // Emit aliases in a deterministic order similar to previous emitter
                fun emitObjectAlias(t: TsType) {
                    val alias = baseAliasName(t.typeScriptTypeName)
                    if (!emitted.add(alias)) return
                    // Ensure any intersected types are emitted first
                    t.intersects.forEach { interName ->
                        val interAlias = baseAliasName(interName)
                        val ref = typesByAlias[interAlias]
                        if (ref != null) {
                            // recursively emit the intersected type alias
                            emitObjectAlias(ref)
                        }
                    }
                    val resolved = typesByAlias[alias] ?: t
                    // Walk fields to emit dependent complex types first
                    fun emitFieldRefType(rt: TsType?) {
                        when (val b = rt?.body) {
                            is TsBody.PrimitiveBody -> {
                                typesByAlias[b.tsName]?.let { emitObjectAlias(it) }
                            }
                            is TsBody.ArrayBody -> emitFieldRefType(b.element)
                            is TsBody.ObjectBody -> {
                                // If this is a named complex type, ensure it is emitted as an alias
                                val nestedAlias = baseAliasName(rt.typeScriptTypeName)
                                val nested = typesByAlias[nestedAlias]
                                if (nested != null) emitObjectAlias(nested)
                                // Also walk its fields for deeper references
                                b.tsFields.forEach { f -> emitFieldRefType(f.type) }
                            }
                            is TsBody.UnionBody -> {
                                b.options.forEach { opt ->
                                    // Emit object options as aliases
                                    if (opt.body is TsBody.ObjectBody) emitObjectAlias(opt)
                                    // And recurse into option field references
                                    emitFieldRefType(opt)
                                }
                            }
                            null -> {}
                        }
                    }
                    val objPre = (resolved.body as? TsBody.ObjectBody)
                    objPre?.tsFields?.forEach { f -> emitFieldRefType(f.type) }
                    appendDocWithReferences(tsLines, resolved)
                    val gp = if (resolved.genericParameters.isNotEmpty()) "<${resolved.genericParameters.joinToString(",")}>" else ""
                    tsLines += "type ${alias}${gp} = {"
                    val obj = (resolved.body as TsBody.ObjectBody)
                    obj.tsFields.forEach { f ->
                        val opt = if (f.optional) "?" else ""
                        val pname = quotePropIfNeeded(f.name)
                        tsLines += "  ${pname}${opt}: ${renderTypeName(f.type)}"
                    }
                    if (resolved.intersects.isNotEmpty()) {
                        // Adjust intersects to include generic arguments when missing by inferring from current object's fields
                        val adjusted = resolved.intersects.map { interName ->
                            val base = baseAliasName(interName)
                            val iface = typesByAlias[base]
                            if (iface != null && iface.genericParameters.isNotEmpty() && !interName.contains('<')) {
                                val ifaceFields = (iface.body as? TsBody.ObjectBody)?.tsFields ?: emptyList()
                                val byName = obj.tsFields.associate { it.name to renderTypeName(it.type) }
                                val args = ifaceFields.mapNotNull { byName[it.name] }
                                if (args.size == ifaceFields.size && args.isNotEmpty()) base + "<" + args.joinToString(", ") + ">" else interName
                            } else interName
                        }
                        tsLines += "} & " + adjusted.joinToString(" & ") { it }
                    } else {
                        tsLines += "}"
                    }
                    tsLines += ""
                }

                fun emitUnionAlias(name: String, jvm: String, u: TsBody.UnionBody) {
                    // Do not emit aliases for unions of only primitive types; they are already
                    // inlined at call sites (e.g., "boolean | null").
                    if (u.options.all { it.body is TsBody.PrimitiveBody }) return
                    val alias = baseAliasName(name)
                    if (!emitted.add(alias)) return
                    // Before emitting the union, emit any referenced complex types used by options (from tsFields)
                    val needed = linkedSetOf<TsType>()
                    u.options.forEach { opt ->
                        val body = opt.body
                        if (body is TsBody.ObjectBody) {
                            // Scan tsFields for primitive references that are actually complex aliases
                            body.tsFields.forEach { f ->
                                val prim = f.type.body as? TsBody.PrimitiveBody
                                if (prim != null) {
                                    val match = typesByAlias[prim.tsName]
                                    if (match != null) {
                                        needed += match
                                        // Also include intersected interfaces of the matched type
                                        match.intersects.forEach { interName ->
                                            typesByAlias[baseAliasName(interName)]?.let { needed += it }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    needed.forEach { refType -> emitObjectAlias(refType) }
                    // Emit each option alias when it's an object (ensures intersects/types exist first)
                    u.options.forEach { opt -> if (opt.body is TsBody.ObjectBody) emitObjectAlias(opt) }
                    // Emit the union alias
                    appendDocComment(tsLines, jvm)
                    val rendered = u.options.joinToString(" | ") { opt -> baseAliasName(opt.typeScriptTypeName) }
                    tsLines += "type $alias = $rendered"
                    tsLines += ""
                }

                // Emit request type aliases first (including generic instantiations that reference base aliases)
                api.apiMethods.forEach { m ->
                    val req = m.requestBodyType
                    when (val b = req?.body) {
                        is TsBody.ObjectBody -> emitObjectAlias(req)
                        is TsBody.PrimitiveBody -> {
                            // If the primitive name corresponds to a known alias (possibly generic instantiation), emit base alias
                            val base = baseAliasName(req.typeScriptTypeName)
                            typesByAlias[base]?.let { emitObjectAlias(it) }
                        }
                        else -> {}
                    }
                }
                // Emit unions and their referenced types
                api.apiMethods.forEach { m ->
                    val res = m.responseBodyType
                    when (val body = res?.body) {
                        is TsBody.UnionBody -> emitUnionAlias(res.typeScriptTypeName, res.jvmQualifiedClassName, body)
                        is TsBody.ObjectBody -> emitObjectAlias(res)
                        else -> {}
                    }
                }
                // Emit any remaining types belonging to this API (same simple class prefix) that haven't been emitted yet
                val prefix = api.cls
                extraction.types
                    .filter { !emitted.contains(baseAliasName(it.typeScriptTypeName)) }
                    .filter { baseAliasName(it.typeScriptTypeName).startsWith(prefix) || (it.jvmQualifiedClassName.substringAfterLast('.').substringBefore('$') == prefix) }
                    .forEach { emitObjectAlias(it) }
            }
        }
        while (tsLines.isNotEmpty() && tsLines.last().isBlank()) tsLines.removeLast()
        return tsLines.joinToString("\n")
    }

    private fun renderTypeName(t: TsType): String = t.typeScriptTypeName

    private fun baseAliasName(name: String): String {
        return name.substringBefore(" & ").substringBefore(" | ").substringBefore("<")
    }

    private fun appendDocComment(target: MutableList<String>, jvmFqn: String?) {
        if (jvmFqn == null) return
        target += "/**"
        target += " * JVM: $jvmFqn"
        target += " */"
    }

    private fun appendDocWithReferences(target: MutableList<String>, t: TsType) {
        val jvmFqn = t.jvmQualifiedClassName
        val refs = t.references
            .map {
                when (it) {
                    is TsRef.ByMethod -> it.controllerJvmQualifiedMethodName
                    is TsRef.ByType -> it.jvmQualifiedClassName
                }
            }
            .distinct()
            .sorted()
        if (jvmFqn.isBlank() && refs.isEmpty()) return
        target += "/**"
        target += " * JVM: $jvmFqn"
        if (refs.isNotEmpty()) {
            target += " * Referenced by:"
            refs.forEach { r -> target += " * - $r" }
        }
        target += " */"
    }

}
