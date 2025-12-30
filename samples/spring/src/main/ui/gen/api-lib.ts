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
