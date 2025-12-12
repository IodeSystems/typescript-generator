import {createContext, useContext, useMemo} from "react";
import {ApiOptions} from "../gen/api-lib";

export type ObjectKey = string | number | symbol

// This allows us to pass around method references without the api object ref
export function bind<T extends Record<ObjectKey, unknown>>(t: T) {
  const bound: Record<ObjectKey, unknown> = {}
  Object.getOwnPropertyNames(Object.getPrototypeOf(t)).map(k => {
    if (k !== "constructor") {
      const member = t[k]
      if (typeof member === "function") {
        bound[k] = member.bind(t)
      }
    }
  })
  return bound as unknown as T
}

export const ApiContext = createContext<ApiOptions>({})

export function useApi<T>(
  ctor: { new(opts: ApiOptions): T }
) {
  const apiOptions = useContext(ApiContext)
  const cache = useMemo(() => new Map<string, unknown>(), [apiOptions])
  const existing: unknown = cache.get(ctor.name)
  if (existing) return existing as T
  const api = new ctor(apiOptions)
  cache.set(ctor.name, api)
  return api
}