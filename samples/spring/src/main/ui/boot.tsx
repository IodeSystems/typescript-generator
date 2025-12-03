import {lazy, StrictMode, Suspense} from "react"
import ReactDOM from "react-dom/client"

const lazyImport = import("./App")

const app = document.getElementById("app")
const AppLazy = lazy(async () => lazyImport)

export const LoadingContent = "Loading..."
const content = (<StrictMode>
  <Suspense fallback={LoadingContent}>
    <AppLazy/>
  </Suspense>
</StrictMode>)


if (app !== null) {
  const w = window as { "react-root"?: ReactDOM.Root }
  if (!w["react-root"]) w["react-root"] = ReactDOM.createRoot(app);
  w["react-root"]?.render(content)
}
