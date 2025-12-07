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

// Don't create too many
const cache = new Map<string, unknown>()


// If we want to globally set ApiOptions, we would use an ApiContext that would provide them
// And return a memo based on the api options for the cache instead of using a global one.
export default function useApi<T>(
  ctor: { new(opts: ApiOptions): T }
) {
  const existing: unknown = cache.get(ctor.name)
  if (existing) return existing as T
  const api = new ctor({})
  cache.set(ctor.name, api)
  return api
}