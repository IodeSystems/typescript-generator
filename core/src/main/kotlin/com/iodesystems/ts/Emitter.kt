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

        fun tsNameWithGenericsResolved(type: TsType): String {
            val name = type.tsName
            val generics = type.tsGenericParameters
            if (generics.isEmpty()) return name

            val lt = name.indexOf('<')
            val gt = name.lastIndexOf('>')
            if (lt == -1 || gt == -1 || gt < lt) return name

            val base = name.take(lt)
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
                    val resolved = tsNameWithGenericsResolved(mapped)
                    if (mapped.isNullable) "$resolved | null" else resolved
                } else arg
            }

            return base + "<" + resolvedArgs.joinToString(",") + ">"
        }
    }

    class WriteContext(
        val file: File,
        val content: StringBuilder = StringBuilder(),
        val ownedTypes: MutableSet<String> = mutableSetOf(),
        val alreadyImported: MutableSet<String> = mutableSetOf(),
    ) {
        fun write(string: String) {
            content.append(string)
        }

        fun importFrom(other: WriteContext): String {
            return "./" + other.file.relativeTo(file.parentFile)
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
                file.file.writeText(file.content.toString())
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
                    ctx.write(line)
                    if (!line.endsWith("\n")) ctx.write("\n")
                }
                if (!isLibExternal || file != libFile) {
                    config.externalImportLines.forEach { (_, line) ->
                        ctx.write(line + "\n")
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

        extraction.types.forEach { type ->
            val o = writeContextForType()
            o.ownedTypes.add(type.tsName)
            typeLocations[type.tsName] = o

            o.write("/**\n")
            o.write(" * Jvm {@link ${type.jvmQualifiedClassName}}\n")
            if (config.includeRefComments) {
                extraction.typeReferences.filter { it.toTsBaseName == type.tsName }
                    .groupBy { it.refType }
                    .forEach { (type, refs) ->
                        o.write(" * $type ref:\n")
                        refs.forEach { ref ->
                            o.write(" * - {@link ${ref.fromTsBaseName}}\n")
                        }
                    }
            }
            o.write(" */\n")

            o.write("export type ${type.tsName} = ")
            when (type) {
                is TsType.Inline -> error("Inline types are NOT exported. This is an error here")
                is TsType.Object -> {

                    val supers = type.supertypes
                    // Alias-like object: no fields, exactly one supertype, no discriminator
                    if (type.fields.isEmpty() && supers.size == 1 && type.discriminator == null) {
                        o.write(tsNameWithGenericsResolved(supers.first()))
                        o.write("\n")
                        return@forEach
                    }
                    if (supers.isNotEmpty()) {
                        o.write(supers.joinToString(" & ") { tsNameWithGenericsResolved(it) })
                        o.write(" & ")
                    }
                    o.write("{\n")
                    val discriminatorKey = type.discriminator?.first
                    type.discriminator?.let { (key, value) ->
                        o.write("  \"${key}\": \"$value\"\n")
                    }
                    type.fields.forEach { (field, f) ->
                        if (discriminatorKey != null && field == discriminatorKey) return@forEach
                        o.write("  ${fieldName(field)}")
                        if (f.optional) {
                            o.write("?")
                        }
                        o.write(": ")
                        val fieldType = tsNameWithGenericsResolved(f.type)
                        o.write(fieldType)
                        if (f.nullable) {
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
                    val unionBody = type.children.joinToString(" | ") { child ->
                        tsNameWithGenericsResolved(child)
                    }
                    if (type.supertypes.isNotEmpty()) {
                        val supers = type.supertypes.joinToString(" & ") { tsNameWithGenericsResolved(it) }
                        o.write("$supers & ($unionBody)\n")
                    } else {
                        o.write("$unionBody\n")
                    }
                }

                is TsType.Enum -> {
                    o.write(type.unionLiteral)
                    o.write("\n")
                }
            }
        }

        val didImportTypes = mutableSetOf<WriteContext>()
        extraction.apis.map { api ->
            val o = api.writeContext()

            if (o != lib) {
                if (didImportTypes.add(o)) {
                    o.write(
                        "import { ApiOptions, fetchInternal, flattenQueryParams } from '${
                            o.importFrom(lib).dropLast(3)
                        }'\n"
                    )
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
                    val names = importTypes.filter {
                        !o.alreadyImported.contains(it)
                    }.toList().sorted()
                    o.alreadyImported.addAll(names)
                    o.write("import { ${names.joinToString(", ")} } from '$path'\n")

                }
            }

            o.write("export class ${api.tsBaseName} {\n")
            o.write("  constructor(private opts: ApiOptions = {}) {}\n")
            api.apiMethods.forEach { method ->
                val req = method.requestBodyType
                val res = method.responseBodyType

                fun withNullability(name: String, t: TsType?): String {
                    if (t == null) return name
                    val nullable = if (t.isNullable) " | null" else ""
                    val optional = if (t.isOptional) " | undefined" else ""
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
                        "${p.name}: ${tsType}"
                    }
                    sigParts += "path: { ${fields} }"
                }
                if (method.queryParamsType != null) {
                    val qn = tsNameWithGenericsResolved(method.queryParamsType)
                    sigParts += "query: ${qn}"
                }
                if (req != null) {
                    val bodyName = tsNameWithGenericsResolved(req)
                    sigParts += (if (req.isOptional) "req?: " else "req: ") + withNullability(bodyName, req)
                }

                val sig = sigParts.joinToString(", ")
                val resName = withNullability(tsNameWithGenericsResolved(res), res)
                o.write("  ${method.name}($sig): Promise<${resName}> {\n")
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
                if (res.tsName == "void") {
                    o.write(".then(r=>{})\n")
                } else {
                    o.write(".then(r=>r.json())\n")
                }
                o.write("  }\n")
            }
            o.write("}\n")
        }
        return Output(writeContexts.values.toList())
    }


}