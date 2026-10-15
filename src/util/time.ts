/** Timestamp formatting: relative times with absolute fallback, cached. */

const rtf = new Intl.RelativeTimeFormat('en', { style: 'narrow' })

const DIVISIONS: { amount: number; unit: Intl.RelativeTimeFormatUnit }[] = [
  { amount: 60, unit: 'second' },
  { amount: 60, unit: 'minute' },
  { amount: 24, unit: 'hour' },
  { amount: 7, unit: 'day' },
  { amount: 4.34524, unit: 'week' },
  { amount: 12, unit: 'month' },
  { amount: Number.POSITIVE_INFINITY, unit: 'year' },
]

export function formatRelative(iso: string, now = Date.now()): string {
  let duration = (new Date(iso).getTime() - now) / 1000
  for (const d of DIVISIONS) {
    if (Math.abs(duration) < d.amount) {
      return rtf.format(Math.round(duration), d.unit)
    }
    duration /= d.amount
  }
  return ''
}

const absDate = new Intl.DateTimeFormat('en', { dateStyle: 'medium', timeStyle: 'short' })
const absDateShort = new Intl.DateTimeFormat('en', { month: 'short', day: 'numeric', year: 'numeric' })
const absTime = new Intl.DateTimeFormat('en', { hour: 'numeric', minute: '2-digit' })

export function formatAbsolute(iso: string): string {
  return absDate.format(new Date(iso))
}

export function formatAbsoluteShort(iso: string): string {
  return absDateShort.format(new Date(iso))
}

export function formatTime(iso: string): string {
  return absTime.format(new Date(iso))
}

/** "3m" / "4h" / "2d" compact form for status headers. */
export function formatCompact(iso: string, now = Date.now()): string {
  const s = Math.max(0, (now - new Date(iso).getTime()) / 1000)
  if (s < 60) return 'now'
  if (s < 3600) return `${Math.floor(s / 60)}m`
  if (s < 86400) return `${Math.floor(s / 3600)}h`
  if (s < 86400 * 365) return `${Math.floor(s / 86400)}d`
  return formatAbsoluteShort(iso)
}

/** 1,234-style counts, compact above 10k. */
export function formatCount(n: number): string {
  if (n < 10000) return n.toLocaleString('en')
  if (n < 1000000) return `${(n / 1000).toFixed(1).replace(/\.0$/, '')}K`
  return `${(n / 1000000).toFixed(1).replace(/\.0$/, '')}M`
}

/** Full-precision counts (1,234 / 12,345). For places where rounding would mislead, such as destructive confirmations. */
export function formatExact(n: number): string {
  return n.toLocaleString('en')
}

/** Human-readable byte sizes for media limits. */
export function formatBytes(n: number): string {
  if (n < 1024) return `${n} B`
  if (n < 1048576) return `${(n / 1024).toFixed(0)} KB`
  return `${(n / 1048576).toFixed(1)} MB`
}
