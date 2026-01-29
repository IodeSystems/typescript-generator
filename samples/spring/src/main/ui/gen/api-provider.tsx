import type {Dayjs} from 'dayjs'
import { ReactNode, useMemo } from 'react'
import { ApiContext, ApiOptions } from './use-api'

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
