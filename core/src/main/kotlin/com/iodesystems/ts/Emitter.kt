package com.iodesystems.ts


import com.iodesystems.ts.model.*

class Emitter(private val config: Config) {

    // Small internal helpers to keep logic cohesive and deterministic
    private object NameRenderer {
        fun render(t: TsType): String =
            // Normalize trivial empty generics sometimes coming from extraction (e.g., Foo<>)
            t.typeScriptTypeName.replace("<>", "")

        fun baseAlias(name: String): String =
            name.substringBefore(" & ").substringBefore(" | ").substringBefore("<")
    }

    private class ImportAccumulator {
        // explicit lines (from config)
        val explicitLines = linkedSetOf<String>()

        // module -> names
        private val importsByModule = linkedMapOf<String, MutableSet<String>>()

        fun addExplicit(line: String?) {
            if (line != null) explicitLines += line
        }

        fun addImport(module: String, name: String) {
            importsByModule.getOrPut(module) { linkedSetOf() }.add(name)
        }

        fun renderInto(lines: MutableList<String>) {
            explicitLines.forEach { lines += it }
            if (importsByModule.isEmpty()) return
            // Stable module and name ordering for determinism
            lines += ""
            importsByModule
                .toSortedMap(compareBy<String> { it })
                .forEach { (module, names) ->
                    val ordered = names.toList().sorted()
                    lines += "import type { ${ordered.joinToString(", ")} } from '$module'"
                }
        }
    }

    private fun quotePropIfNeeded(name: String): String {
        // Valid TypeScript identifier or property name without quoting
        // Allow '@' in property names to match project expectations (e.g., @type)
        val ident = Regex("^[A-Za-z_$][A-Za-z0-9_$]*$")
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

    fun emitTypes(types: List<TsType>, typeSource: Map<String, String> = emptyMap()): String {
        if (types.isEmpty()) return ""
        val inFileAliases = types.map { NameRenderer.baseAlias(it.typeScriptTypeName) }.toSet()
        val currentModule = typeSource.let { ts ->
            val mods = types.mapNotNull { t -> ts[NameRenderer.baseAlias(t.typeScriptTypeName)] }.toSet()
            if (mods.size > 1) throw IllegalStateException("All provided types must target the same module. Found: $mods")
            mods.firstOrNull()
        }
        val tsLines = mutableListOf<String>()
        run {
            val acc = ImportAccumulator()
            fun walkRefs(t: TsType?) {
                when (val b = t?.body) {
                    is TsBody.PrimitiveBody -> {
                        val a = NameRenderer.baseAlias(b.tsName)
                        if (!inFileAliases.contains(a)) acc.addExplicit(config.externalImportLines[a])
                    }

                    is TsBody.ArrayBody -> walkRefs(b.element)
                    is TsBody.ObjectBody -> {
                        val a = NameRenderer.baseAlias(t.typeScriptTypeName)
                        if (!inFileAliases.contains(a)) acc.addExplicit(config.externalImportLines[a])
                        b.tsFields.forEach { f -> walkRefs(f.type) }
                    }

                    is TsBody.UnionBody -> b.options.forEach { opt ->
                        val a = NameRenderer.baseAlias(opt.typeScriptTypeName)
                        if (!inFileAliases.contains(a)) acc.addExplicit(config.externalImportLines[a])
                        walkRefs(opt)
                    }

                    null -> {}
                }
            }
            types.forEach { walkRefs(it) }
            acc.renderInto(tsLines)
        }

        // Collect cross-file imports when typeSource is provided (split across files)
        val importsByModule = linkedMapOf<String, MutableSet<String>>()
        val externalImports = linkedMapOf<String, MutableSet<String>>()
        // Note: explicit external import lines already handled above

        // Future: populate importsByModule/externalImports as needed
        externalImports.forEach { (mod, names) ->
            tsLines += "import type { ${names.joinToString(", ")} } from '$mod'"
        }
        importsByModule.forEach { (mod, names) ->
            tsLines += "import type { ${names.joinToString(", ")} } from './$mod'"
        }
        if (importsByModule.isNotEmpty()) tsLines += ""

        val emitted = HashSet<String>()

        // Only allow emitting types that belong to this file (if typeSource provided). Others must be imported.
        fun canEmit(alias: String): Boolean {
            val mod = typeSource[alias]
            return mod == currentModule
        }

        fun emitGenerics(t: TsType): String {
            return if (t.genericParameters.isNotEmpty()) "<" + t.genericParameters.joinToString(",") + ">" else ""
        }

        fun emitRef(t: TsType): String = NameRenderer.baseAlias(t.typeScriptTypeName) + emitGenerics(t)

        fun emitObjectAlias(t: TsType) {
            val alias = NameRenderer.baseAlias(t.typeScriptTypeName)
            if (!canEmit(alias)) return
            if (!emitted.add(alias)) return
            // Do not force emission of intersected/external aliases; only recurse for in-file ones
            val resolved = t
            appendDocWithReferences(tsLines, resolved)

            tsLines += "export type $alias${emitGenerics(resolved)} = {"
            val obj = (resolved.body as TsBody.ObjectBody)
            obj.tsFields.forEach { f ->
                val opt = if (f.optional) "?" else ""
                val pname = quotePropIfNeeded(f.name)
                tsLines += "  $pname" + opt + ": " + renderTypeName(f.type)
            }
            if (resolved.intersects.isNotEmpty()) {
                val adjusted = resolved.intersects.map { emitRef(it) }
                tsLines += "} & " + adjusted.joinToString(" & ")
            } else {
                tsLines += "}"
            }
            tsLines += ""
        }

        fun emitUnionAlias(name: String, jvm: String, u: TsBody.UnionBody) {
            if (u.options.all { it.body is TsBody.PrimitiveBody }) return
            val alias = NameRenderer.baseAlias(name)
            if (!canEmit(alias)) return
            if (!emitted.add(alias)) return
            appendDocComment(tsLines, jvm)
            val rendered = u.options.joinToString(" | ") { opt -> NameRenderer.baseAlias(opt.typeScriptTypeName) }
            tsLines += "export type $alias = $rendered"
            tsLines += ""
        }

        // Emit objects then unions, deterministic alphabetical by alias
        types
            .filter { it.body is TsBody.ObjectBody }
            .sortedBy { NameRenderer.baseAlias(it.typeScriptTypeName) }
            .forEach { emitObjectAlias(it) }
        types
            .mapNotNull { t -> (t.body as? TsBody.UnionBody)?.let { Pair(t, it) } }
            .sortedBy { NameRenderer.baseAlias(it.first.typeScriptTypeName) }
            .forEach { (t, ub) -> emitUnionAlias(t.typeScriptTypeName, t.jvmQualifiedClassName, ub) }
        return tsLines.joinToString("\n")
    }

    // New API: emit APIs for a single output module. When typeSource is null, inline types.
    fun emitApis(
        apis: List<ApiModel>,
        extraction: ExtractionResult,
        typeSource: Map<String, String> = emptyMap(),
        libModule: String? = null,
        skipLibDeclaration: Boolean = false
    ): String {
        val tsLines = mutableListOf<String>()
        val selected = apis.filter { config.includeApi(it.jvmQualifiedClassName) }
        // Keep a single emitted set across all selected APIs to avoid duplicating type aliases when inlining
        val emitted = HashSet<String>()

        // Indexes for resolving canonical declarations
        val typesByExactName = extraction.types.associateBy { it.typeScriptTypeName }
        val typesByBaseName: Map<String, List<TsType>> =
            extraction.types.groupBy { NameRenderer.baseAlias(it.typeScriptTypeName) }
        fun resolveCanonical(base: String): TsType? {
            val list = typesByBaseName[base] ?: return null
            // Prefer object declarations, prefer generic object first, then non-generic, then others
            return list.sortedWith(compareBy<TsType>({ t ->
                when (t.body) {
                    is TsBody.ObjectBody -> 0
                    is TsBody.UnionBody -> 1
                    is TsBody.PrimitiveBody -> 2
                    is TsBody.ArrayBody -> 3
                }
            }, { t -> if (t.genericParameters.isNotEmpty() || t.typeScriptTypeName.contains("<")) 0 else 1 }))
                .firstOrNull()
        }

        if (!skipLibDeclaration) {
            // Import lib if requested, otherwise inline lib
            if (libModule != null) {
                tsLines += "import { ApiOptions, fetchInternal, flattenQueryParams } from '${libModule.removeSuffix(".ts")}'"
            } else {
                // Inline the library helpers at the top of the file
                tsLines += emitLib()
                tsLines += ""
            }
        }

        val importsByModule = linkedMapOf<String, MutableSet<String>>()
        val explicitImportLines = linkedSetOf<String>()
        val toInline = linkedSetOf<String>()
        fun baseAlias(name: String): String = NameRenderer.baseAlias(name)

        fun aliasWithGenerics(t: TsType): String {
            val name = t.typeScriptTypeName
            if (name.contains("<")) return NameRenderer.render(t)
            return baseAlias(name) + if (t.genericParameters.isNotEmpty()) {
                "<${t.genericParameters.joinToString(",")}>"
            } else ""
        }

        val walked = HashSet<TsType>()
        fun walk(t: TsType?) {
            if (t == null) return
            if (walked.contains(t)) return
            walked += t
            t.intersects.forEach { walk(it) }
            when (val b = t.body) {
                is TsBody.ArrayBody -> walk(b.element)
                is TsBody.PrimitiveBody -> {
                    val a = baseAlias(b.tsName)
                    val explicit = config.externalImportLines[a]
                    if (explicit != null) {
                        explicitImportLines += explicit
                    }
                    // If this alias is provided by a local types module, collect it, else inline
                    val mod = typeSource[a]
                    if (mod != null) importsByModule.getOrPut(mod) { linkedSetOf() }.add(a) else toInline += a
                }

                is TsBody.ObjectBody -> {
                    val a = baseAlias(t.typeScriptTypeName)
                    val explicit = config.externalImportLines[a]
                    if (explicit != null) {
                        explicitImportLines += explicit
                    }
                    // Collect object alias if sourced from types file, else inline
                    val mod = typeSource[a]
                    if (mod != null) importsByModule.getOrPut(mod) { linkedSetOf() }.add(a) else toInline += a
                    b.tsFields.forEach { f -> walk(f.type) }
                }

                is TsBody.UnionBody -> {
                    // Handle the parent union alias itself first
                    val parentAlias = baseAlias(t.typeScriptTypeName)
                    config.externalImportLines[parentAlias]?.let { explicitImportLines += it }
                    val parentMod = typeSource[parentAlias]
                    if (parentMod != null) {
                        // Import only the parent union alias when available in types file
                        importsByModule.getOrPut(parentMod) { linkedSetOf() }.add(parentAlias)
                        // Do not import option aliases; they are not referenced by API signatures
                    } else {
                        // Inline the parent union and its option aliases when not present in typeSource
                        toInline += parentAlias
                        b.options.forEach { opt ->
                            val optAlias = baseAlias(opt.typeScriptTypeName)
                            config.externalImportLines[optAlias]?.let { explicitImportLines += it }
                            toInline += optAlias
                            walk(opt)
                        }
                    }
                }
            }
        }
        selected.forEach { api ->
            api.apiMethods.forEach { m ->
                walk(m.requestBodyType)
                walk(m.queryParamsType)
                m.pathTsFields.values.forEach { f -> walk(f.type) }
                walk(m.responseBodyType)
            }
        }
        // When not splitting types (no typeSource), inline all object aliases referenced by extraction
        if (typeSource.isEmpty()) {
            extraction.types.filter { it.body is TsBody.ObjectBody }
                .forEach { t -> toInline += NameRenderer.baseAlias(t.typeScriptTypeName) }
        }
        // Deterministic rendering of import lines and module imports
        explicitImportLines.forEach { line -> tsLines += line }
        if (importsByModule.isNotEmpty()) tsLines += ""
        // Prefer object aliases before union aliases in import name ordering
        val typesByBase = extraction.types.associateBy { NameRenderer.baseAlias(it.typeScriptTypeName) }
        importsByModule.toSortedMap(compareBy { it }).forEach { (module, imports) ->
            val ordered = imports.toList().sortedWith(compareBy({ alias ->
                val body = typesByBase[alias]?.body
                when (body) {
                    is TsBody.ObjectBody -> 0
                    is TsBody.UnionBody -> 1
                    else -> 2
                }
            }, { it }))
            tsLines += "import type { ${ordered.joinToString(", ")} } from '$module'"
        }
        // Prepare an inliner that will only emit aliases present in toInline
        fun shouldInline(alias: String): Boolean = typeSource.isEmpty() || alias in toInline
        fun emitTypeDefinition(t: TsType) {
            val alias = NameRenderer.baseAlias(t.typeScriptTypeName)
            if (!shouldInline(alias)) return
            // Resolve to a canonical declaration (prefer object bodies)
            val resolvedPre = resolveCanonical(alias) ?: t
            // If this is just a bound generic primitive reference and we cannot resolve a canonical declaration,
            // skip for now to allow a later canonical emission path to handle it.
            if (resolvedPre.body is TsBody.PrimitiveBody && t.typeScriptTypeName.contains("<") && resolveCanonical(alias) == null) {
                return
            }
            if (!emitted.add(alias)) return
            // Now operate on the resolved canonical (if available)
            val resolved = resolveCanonical(alias) ?: t
            resolved.intersects.forEach { ref ->
                val rAlias = NameRenderer.baseAlias(ref.typeScriptTypeName)
                val resolvedRef = resolveCanonical(rAlias) ?: ref
                emitTypeDefinition(resolvedRef)
            }
            fun emitFieldRefType(rt: TsType?) {
                when (val b = rt?.body) {
                    is TsBody.PrimitiveBody -> {
                        val primAlias = NameRenderer.baseAlias(b.tsName)
                        resolveCanonical(primAlias)?.let { emitTypeDefinition(it) }
                    }

                    is TsBody.ArrayBody -> emitFieldRefType(b.element)
                    is TsBody.ObjectBody -> {
                        val nestedAlias = NameRenderer.baseAlias(rt.typeScriptTypeName)
                        val nested = resolveCanonical(nestedAlias)
                        if (nested != null) emitTypeDefinition(nested)
                        b.tsFields.forEach { f -> emitFieldRefType(f.type) }
                    }

                    is TsBody.UnionBody -> {
                        b.options.forEach { opt ->
                            if (opt.body is TsBody.ObjectBody) emitTypeDefinition(opt)
                            emitFieldRefType(opt)
                        }
                    }

                    null -> {}
                }
            }
            // Emit fields and refs
            when (val b = resolved.body) {
                is TsBody.ObjectBody -> {
                    appendDocWithReferences(tsLines, resolved)
                    val intersects = if (resolved.intersects.isNotEmpty()) {
                        " & " + resolved.intersects.joinToString(" & ") {
                            aliasWithGenerics(it)
                        }
                    } else ""
                    val typeName = if (resolved.typeScriptTypeName.contains("<")) {
                        NameRenderer.render(resolved)
                    } else if (resolved.genericParameters.isNotEmpty()) {
                        resolved.typeScriptTypeName + "<" + resolved.genericParameters.joinToString(",") + ">"
                    } else {
                        resolved.typeScriptTypeName
                    }
                    tsLines += "type ${typeName} = {"
                    b.tsFields.forEach { f ->
                        val q = if (f.optional) "?" else ""
                        tsLines += "  ${quotePropIfNeeded(f.name)}${q}: ${renderTypeName(f.type)}"
                        emitFieldRefType(f.type)
                    }
                    tsLines += "}$intersects"
                    tsLines += ""
                }

                is TsBody.UnionBody -> {
                    val n = resolved.typeScriptTypeName
                    appendDocWithReferences(tsLines, resolved)
                    tsLines += "type $n = " + b.options.joinToString(" | ") { renderTypeName(it) }
                    tsLines += ""
                }

                is TsBody.PrimitiveBody -> {
                    val leftName = resolved.typeScriptTypeName
                    val hasGenericsOnLeft = leftName.contains("<")
                    val base = NameRenderer.baseAlias(leftName)
                    if (hasGenericsOnLeft) {
                        // This is a bound generic reference like Foo<Bar>; ensure canonical Foo<A,B> is emitted
                        resolveCanonical(base)?.let { if (it.body is TsBody.ObjectBody) emitTypeDefinition(it) }
                        return
                    }
                    // Otherwise, this represents a simple alias to a primitive/external type. Emit alias.
                    appendDocWithReferences(tsLines, resolved)
                    val right = b.tsName
                    val intersects = if (resolved.intersects.isNotEmpty()) {
                        " & " + resolved.intersects.joinToString(" & ") { aliasWithGenerics(it) }
                    } else ""
                    tsLines += "type ${leftName} = ${right}${intersects}"
                    tsLines += ""
                }

                else -> error("Unexpected type: $b")
            }
        }

        fun emitUnionAlias(name: String, u: TsBody.UnionBody) {
            if (!shouldInline(NameRenderer.baseAlias(name))) return
            tsLines += "type $name = " + u.options.joinToString(" | ") { renderTypeName(it) }
            tsLines += ""
        }
        // Emit API classes first (as expected by tests)
        selected.forEach { api ->
            tsLines += "export class ${api.tsBaseName} {"
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
                    val pathObj = pathFields.entries.joinToString(", ") { (_, field) ->
                        val opt = if (field.optional) "?" else ""
                        "${field.name}${opt}: ${renderTypeName(field.type)}"
                    }
                    argParts += "path: { $pathObj }"
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
                    "\"${m.path}\"$rep"
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
                tsLines += if (resTs == "void") {
                    "    }).then(r=>undefined as any)"
                } else {
                    "    }).then(r=>r.json())"
                }
                tsLines += "  }"
            }
            tsLines += "}"
            tsLines += ""
        }
        // Then inline eligible object and union aliases in deterministic order
        // First, proactively emit any aliases we explicitly decided to inline while walking (e.g.,
        // generic parents like PolyIContainer) to avoid losing canonical definitions due to bound refs.
        toInline.toList().sorted().forEach { a ->
            resolveCanonical(a)?.let { emitTypeDefinition(it) }
        }
        // Additionally, if canonical resolution failed for some aliases, try direct lookup in extraction types
        // to find an object declaration to emit (addresses cases where a bound alias shadowed grouping).
        extraction.types
            .filter { it.body is TsBody.ObjectBody }
            .filter { toInline.contains(NameRenderer.baseAlias(it.typeScriptTypeName)) }
            .sortedBy { NameRenderer.baseAlias(it.typeScriptTypeName) }
            .forEach { emitTypeDefinition(it) }
        extraction.types
            .filter { it.body is TsBody.ObjectBody }
            .sortedBy { NameRenderer.baseAlias(it.typeScriptTypeName) }
            .forEach { emitTypeDefinition(it) }
        // Ensure intersected aliases are also emitted even if not top-level in extraction
        extraction.types
            .flatMap { it.intersects }
            .sortedBy { NameRenderer.baseAlias(it.typeScriptTypeName) }
            .forEach { emitTypeDefinition(it) }
        extraction.types
            .mapNotNull { t -> (t.body as? TsBody.UnionBody)?.let { Pair(t, it) } }
            .sortedBy { NameRenderer.baseAlias(it.first.typeScriptTypeName) }
            .forEach { (t, u) -> emitUnionAlias(t.typeScriptTypeName, u) }
        while (tsLines.isNotEmpty() && tsLines.last().isBlank()) tsLines.removeLast()
        return tsLines.joinToString("\n")
    }

    private fun renderTypeName(t: TsType): String = NameRenderer.render(t)

    private fun baseAliasName(name: String): String = NameRenderer.baseAlias(name)

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
