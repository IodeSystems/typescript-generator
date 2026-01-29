import type {Dayjs} from 'dayjs'
import { createContext, useContext, useMemo } from 'react'
import type { ApiOptions } from './api-lib'

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
