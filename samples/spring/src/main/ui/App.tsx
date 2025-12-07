import {useCallback, useMemo, useState} from "react"
import {SampleApi} from "./gen/api"

function getApiBaseUrl(): string | undefined {
  try {
    const url = new URL(window.location.href)
    const api = url.searchParams.get("api")
    return api ?? undefined
  } catch {
    return undefined
  }
}

export default function App() {
  const [result, setResult] = useState<string>("")
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string>("")

  const api = useMemo(() => new SampleApi({ baseUrl: getApiBaseUrl() }), [])

  const onPing = useCallback(async () => {
    setLoading(true)
    setError("")
    setResult("")
    try {
      const res = await api.ping({ message: "ping" })
      const anyRes = res as any
      const msg = anyRes?.message ?? anyRes?.result ?? "unknown"
      setResult(String(msg))
    } catch (e: any) {
      setError(e?.message ?? String(e))
    } finally {
      setLoading(false)
    }
  }, [api])

  return (
    <div>
      <button data-testid="ping-button" onClick={onPing} disabled={loading}>Ping</button>
      {loading && <div data-testid="loading">Loading...</div>}
      {error && <div data-testid="error">{error}</div>}
      <div data-testid="pong-text">{result}</div>
    </div>
  )
}