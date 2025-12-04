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