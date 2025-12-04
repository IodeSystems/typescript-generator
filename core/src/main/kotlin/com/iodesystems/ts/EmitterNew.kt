package com.iodesystems.ts

import com.iodesystems.ts.model.ApiModelNew
import com.iodesystems.ts.model.ExtractionResultNew
import com.iodesystems.ts.model.TsTypeNew
import java.io.File

class EmitterNew(
    private val config: Config,
    private val extraction: ExtractionResultNew
) {

    companion object {
        fun tsNameWithGenericsResolved(type: TsTypeNew): String {
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
    ) {
        fun write(string: String) {
            content.append(string)
        }

        fun importFrom(other: WriteContext): String {
            return other.file.relativeTo(file).toString()
        }
    }

    data class Output(
        val files: List<WriteContext>
    )


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

        fun ApiModelNew.writeContext(): WriteContext {
            val file = apiFileMapping?.firstNotNullOf { (file, matches) ->
                if (matches.any { it == jvmQualifiedClassName }) file
                else if (matches.any { regexCache.getOrPut(it) { Regex(it) }.matches(jvmQualifiedClassName) }) file
                else null
            } ?: "api.ts"
            return writeContexts.getOrPut(file) {
                WriteContext(outputDir.resolve(file))
            }
        }

        fun writeContext(): WriteContext {
            val typesFile = typesFile ?: "api.ts"
            return writeContexts.getOrPut(typesFile) { WriteContext(outputDir.resolve(typesFile)) }
        }

        val lib = writeContexts.getOrPut(libFile) { WriteContext(outputDir.resolve(libFile)) }
        lib.write(lib())
        lib.write("\n")

        extraction.types.forEach { type ->
            val o = writeContext()
            typeLocations[type.tsName] = o
            o.write("export type ${type.tsName} = ")
            when (type) {
                is TsTypeNew.Inline -> {
                    TODO("Does this even happen, I don't think so, not in all the tests")
                }

                is TsTypeNew.Object -> {
                    o.write("{\n")
                    type.discriminator?.let { (key, value) ->
                        o.write("  \"${key}\": \"$value\"\n")
                    }
                    type.fields.forEach { (field, type) ->
                        o.write("  $field: ")
                        o.write("${type.tsName}\n")
                    }
                    o.write("}\n")
                }

                is TsTypeNew.Union -> {
                    o.write(type.children.joinToString(" | ") { child ->
                        child.tsName
                    })
                    o.write("\n")
                }
            }
        }

        extraction.apis.map { api ->
            val o = api.writeContext()
            if (o != lib) o.write(
                "import { ApiOptions, fetchInternal, flattenQueryParams } from '${
                    lib.importFrom(o)
                }'\n"
            )
            o.write("export class ${api.tsBaseName} {\n")
            o.write("  constructor(private opts: ApiOptions = {}) {}\n")
            api.apiMethods.forEach { method ->
                val req = method.requestBodyType
                val res = method.responseBodyType
                val sig = listOfNotNull(
                    req?.let { body ->
                        "req:" + tsNameWithGenericsResolved(body)
                    }
                ).joinToString(", ")
                o.write("  ${method.name}($sig): Promise<${tsNameWithGenericsResolved(method.responseBodyType)}> {\n")
                o.write("    return fetchInternal(this.opts, ")
                if (method.queryParamsType != null) {
                    o.write("flattenQueryParams(")
                }
                o.write("\"${method.path}\"")
                method.pathTsFields.forEach { repl ->
                    o.write(".replace(\"{${repl.placeholder}}\", String(path.${repl.field}))")
                }
                if (method.queryParamsType != null) {
                    o.write(", query, null)")
                }
                o.write(", {\n")
                o.write("      method: \"${method.httpMethod.name}\",\n")
                if (req != null) {
                    o.write("      headers: {'Content-Type': 'application/json'},\n")
                    o.write("      body: JSON.stringify(req)\n")
                }
                o.write("    })")
                if (res.tsName == "void") {
                    o.write(".then(r=> {})\n")
                } else {
                    o.write(".then(r=>r.json())\n")
                }
                o.write("  }\n")
            }
            o.write("}\n")
        }
        return Output(writeContexts.values.toList())
    }


    private fun lib(): String = """
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
}