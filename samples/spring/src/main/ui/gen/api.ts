import { AbortablePromise, ApiOptions, fetchInternal } from './api-lib'
import { EventApiCreate, EventApiCreateResponseUnion } from './api-types'
/**
 * Jvm {@link com.iodesystems.ts.sample.api.EventApi}
 */
export class EventApi {
  constructor(private opts: ApiOptions = {}) {}
  create(req: EventApiCreate): AbortablePromise<EventApiCreateResponseUnion> {
    return fetchInternal(this.opts, "/api/orgs/events/create", {
      method: "POST",
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify(req)
    }).then(r=>r.json())
  }
}
import { RefUnion, SampleApiAdd, SampleApiAddResponseUnion, SampleApiIsPrefixTest, SampleApiPing, SampleApiPingResponseUnion, SampleApiWrapper, SlugRefUnion } from './api-types'
/**
 * Jvm {@link com.iodesystems.ts.sample.api.SampleApi}
 */
export class SampleApi {
  constructor(private opts: ApiOptions = {}) {}
  add(req: SampleApiAdd): AbortablePromise<SampleApiAddResponseUnion> {
    return fetchInternal(this.opts, "/api/sample", {
      method: "POST",
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify(req)
    }).then(r=>r.json())
  }
  ping(req: SampleApiPing): AbortablePromise<SampleApiPingResponseUnion> {
    return fetchInternal(this.opts, "/api/sample/ping", {
      method: "POST",
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify(req)
    }).then(r=>r.json())
  }
  getRef(): AbortablePromise<RefUnion> {
    return fetchInternal(this.opts, "/api/sample/ref", {
      method: "GET"
    }).then(r=>r.json())
  }
  getSlugRef(): AbortablePromise<SlugRefUnion> {
    return fetchInternal(this.opts, "/api/sample/slug-ref", {
      method: "GET"
    }).then(r=>r.json())
  }
  getWrapped(): AbortablePromise<SampleApiWrapper> {
    return fetchInternal(this.opts, "/api/sample/wrapped", {
      method: "GET"
    }).then(r=>r.json())
  }
  getIsPrefixTest(): AbortablePromise<SampleApiIsPrefixTest> {
    return fetchInternal(this.opts, "/api/sample/is-prefix-test", {
      method: "GET"
    }).then(r=>r.json())
  }
}
