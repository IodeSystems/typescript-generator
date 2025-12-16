import type {Dayjs} from 'dayjs'
import { ApiOptions, fetchInternal, flattenQueryParams } from './api-lib'
import { RefUnion, SampleApiAdd, SampleApiAddResponseUnion, SampleApiPing, SampleApiPingResponseUnion, SlugRefUnion } from './api-types'
export class SampleApi {
  constructor(private opts: ApiOptions = {}) {}
  add(req: SampleApiAdd): Promise<SampleApiAddResponseUnion> {
    return fetchInternal(this.opts, "/api/sample", {
      method: "POST",
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify(req)
    }).then(r=>r.json())
  }
  ping(req: SampleApiPing): Promise<SampleApiPingResponseUnion> {
    return fetchInternal(this.opts, "/api/sample/ping", {
      method: "POST",
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify(req)
    }).then(r=>r.json())
  }
  getRef(): Promise<RefUnion> {
    return fetchInternal(this.opts, "/api/sample/ref", {
      method: "GET"
    }).then(r=>r.json())
  }
  getSlugRef(): Promise<SlugRefUnion> {
    return fetchInternal(this.opts, "/api/sample/slug-ref", {
      method: "GET"
    }).then(r=>r.json())
  }
}
