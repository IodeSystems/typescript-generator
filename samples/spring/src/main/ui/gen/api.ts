import type {Dayjs} from 'dayjs'
import { ApiOptions, fetchInternal, flattenQueryParams } from './api-lib'
import { SampleApiAdd, SampleApiAddResponseUnion } from './api-types'
export class SampleApi {
  constructor(private opts: ApiOptions = {}) {}
  add(req: SampleApiAdd): Promise<SampleApiAddResponseUnion> {
    return fetchInternal(this.opts, "/api/sample", {
      method: "POST",
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify(req)
    }).then(r=>r.json())
  }
}
