import {lazy, StrictMode, Suspense, useMemo} from "react"
import ReactDOM from "react-dom/client"
import {ApiProvider} from "./gen/api-provider"

const lazyImport = import("./App")

const app = document.getElementById("app")
const AppLazy = lazy(async () => lazyImport)

function getApiBaseUrl(): string | undefined {
  try {
    const url = new URL(window.location.href)
    const api = url.searchParams.get("api")
    return api ?? undefined
  } catch {
    return undefined
  }
}

export const LoadingContent = "Loading..."

function Root() {
  const apiOptions = useMemo(() => ({ baseUrl: getApiBaseUrl() }), [])
  return (
    <StrictMode>
      <ApiProvider options={apiOptions}>
        <Suspense fallback={LoadingContent}>
          <AppLazy/>
        </Suspense>
      </ApiProvider>
    </StrictMode>
  )
}

if (app !== null) {
  const w = window as { "react-root"?: ReactDOM.Root }
  if (!w["react-root"]) w["react-root"] = ReactDOM.createRoot(app);
  w["react-root"]?.render(<Root/>)
}
