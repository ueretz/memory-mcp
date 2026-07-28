const RELATIVE = new Intl.RelativeTimeFormat(undefined, { numeric: 'auto' })

const UNITS: Array<[Intl.RelativeTimeFormatUnit, number]> = [
  ['year', 365 * 24 * 3600_000],
  ['month', 30 * 24 * 3600_000],
  ['week', 7 * 24 * 3600_000],
  ['day', 24 * 3600_000],
  ['hour', 3600_000],
  ['minute', 60_000],
]

/** "3 days ago" — falls back to a plain date for anything older than a year. */
export function relativeTime(iso: string): string {
  const time = Date.parse(iso)
  if (Number.isNaN(time)) {
    return ''
  }
  const diff = time - Date.now()
  const abs = Math.abs(diff)
  if (abs < 60_000) {
    return 'just now'
  }
  for (const [unit, ms] of UNITS) {
    if (abs >= ms) {
      return RELATIVE.format(Math.round(diff / ms), unit)
    }
  }
  return absoluteDate(iso)
}

export function absoluteDate(iso: string): string {
  const time = Date.parse(iso)
  return Number.isNaN(time) ? '' : new Date(time).toLocaleDateString()
}

export function absoluteDateTime(iso: string): string {
  const time = Date.parse(iso)
  return Number.isNaN(time) ? '' : new Date(time).toLocaleString()
}
