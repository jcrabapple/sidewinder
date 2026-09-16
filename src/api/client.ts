/** Thin fetch wrapper for the Mastodon-compatible API with auth, errors, and pagination. */

export class ApiError extends Error {
  status: number
  body: unknown
  constructor(status: number, message: string, body?: unknown) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.body = body
  }
}

export class NetworkError extends Error {
  cause?: unknown
  constructor(message: string, cause?: unknown) {
    super(message)
    this.name = 'NetworkError'
    this.cause = cause
  }
}

export interface Page<T> {
  items: T[]
  /** Link-header max_id for the next page, or null when exhausted. */
  nextMaxId: string | null
}

// Cloudflare in front of GTS 401s non-browser user agents (error 1010).
const UA = 'Sidewinder/0.1 (+https://github.com/jcrabapple/sidewinder)'

export class Api {
  base: string
  token: string | null = null

  constructor(base: string) {
    this.base = base.replace(/\/+$/, '')
  }

  static async fetchWithTimeout(input: string, init: RequestInit = {}, ms = 30000): Promise<Response> {
    const ctrl = new AbortController()
    const t = setTimeout(() => ctrl.abort(), ms)
    try {
      return await fetch(input, { ...init, signal: ctrl.signal })
    } catch (e) {
      throw new NetworkError(e instanceof Error ? e.message : String(e), e)
    } finally {
      clearTimeout(t)
    }
  }

  async request<T>(
    path: string,
    opts: { method?: string; body?: unknown; query?: Record<string, string | number | boolean | undefined | null>; headers?: Record<string, string>; timeoutMs?: number } = {},
  ): Promise<T> {
    const url = new URL(this.base + path)
    if (opts.query) {
      for (const [k, v] of Object.entries(opts.query)) {
        if (v !== undefined && v !== null) url.searchParams.set(k, String(v))
      }
    }
    const headers: Record<string, string> = { ...opts.headers }
    if (this.token) headers['Authorization'] = `Bearer ${this.token}`
    let body: BodyInit | undefined
    if (opts.body !== undefined) {
      if (opts.body instanceof FormData) {
        body = opts.body
      } else {
        headers['Content-Type'] = 'application/json'
        body = JSON.stringify(opts.body)
      }
    }
    let res: Response
    try {
      res = await Api.fetchWithTimeout(url.toString(), { method: opts.method ?? 'GET', headers, body }, opts.timeoutMs)
    } catch (e) {
      if (e instanceof NetworkError) throw e
      throw e
    }
    if (!res.ok) {
      let payload: unknown = null
      let message = `${res.status} ${res.statusText}`
      try {
        const text = await res.text()
        try {
          payload = JSON.parse(text)
          const p = payload as { error?: string }
          if (p && typeof p.error === 'string') message = p.error
        } catch {
          payload = text.slice(0, 500)
        }
      } catch { /* body unreadable; keep defaults */ }
      throw new ApiError(res.status, message, payload)
    }
    if (res.status === 204) return undefined as T
    return (await res.json()) as T
  }

  /** GET a paginated collection using the Link header's next page. */
  async page<T>(
    path: string,
    opts: { query?: Record<string, string | number | boolean | undefined | null>; headers?: Record<string, string>; timeoutMs?: number } = {},
  ): Promise<Page<T>> {
    const url = new URL(this.base + path)
    if (opts.query) {
      for (const [k, v] of Object.entries(opts.query)) {
        if (v !== undefined && v !== null) url.searchParams.set(k, String(v))
      }
    }
    const headers: Record<string, string> = { ...(opts.headers ?? {}) }
    if (this.token) headers['Authorization'] = `Bearer ${this.token}`
    let res: Response
    try {
      res = await Api.fetchWithTimeout(url.toString(), { headers }, opts.timeoutMs)
    } catch (e) {
      if (e instanceof NetworkError) throw e
      throw e
    }
    if (!res.ok) {
      let message = `${res.status} ${res.statusText}`
      try {
        const p = (await res.json()) as { error?: string }
        if (p?.error) message = p.error
      } catch { /* keep default */ }
      throw new ApiError(res.status, message)
    }
    const items = (await res.json()) as T[]
    let nextMaxId: string | null = null
    const link = res.headers.get('Link') ?? res.headers.get('link')
    if (link) {
      const m = link.match(/<[^>]*[?&]max_id=(\d+)[^>]*>;\s*rel="next"/)
      if (m) nextMaxId = m[1]
    }
    return { items, nextMaxId }
  }

  get<T>(path: string, query?: Record<string, string | number | boolean | undefined | null>): Promise<T> {
    return this.request<T>(path, { query })
  }
  post<T>(path: string, body?: unknown): Promise<T> {
    return this.request<T>(path, { method: 'POST', body })
  }
  put<T>(path: string, body?: unknown): Promise<T> {
    return this.request<T>(path, { method: 'PUT', body })
  }
  patch<T>(path: string, body?: unknown): Promise<T> {
    return this.request<T>(path, { method: 'PATCH', body })
  }
  delete<T>(path: string, body?: unknown): Promise<T> {
    return this.request<T>(path, { method: 'DELETE', body })
  }
}

/** Form-encoded POST (OAuth token endpoints want application/x-www-form-urlencoded). */
export async function postForm<T>(base: string, path: string, form: Record<string, string>): Promise<T> {
  const res = await Api.fetchWithTimeout(base.replace(/\/+$/, '') + path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams(form).toString(),
  })
  if (!res.ok) {
    let message = `${res.status} ${res.statusText}`
    try {
      const p = (await res.json()) as { error?: string; error_description?: string }
      message = p.error_description ?? p.error ?? message
    } catch { /* keep default */ }
    throw new ApiError(res.status, message)
  }
  return (await res.json()) as T
}
