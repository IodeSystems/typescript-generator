import {useCallback, useState} from "react"
import {SampleApi} from "./gen/api"
import {useApi} from "./gen/use-api"

export default function App() {
  const [result, setResult] = useState<string>("")
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string>("")

  const api = useApi(SampleApi)

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
