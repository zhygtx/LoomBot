export function permissionPatternMatches(pattern: string, required: string): boolean {
  const segments = pattern.split('*')
  if (segments.length === 1) return pattern === required

  const prefix = segments[0] ?? ''
  if (prefix && !required.startsWith(prefix)) return false

  let cursor = prefix.length
  for (const segment of segments.slice(1, -1)) {
    if (!segment) continue
    const nextIndex = required.indexOf(segment, cursor)
    if (nextIndex < 0) return false
    cursor = nextIndex + segment.length
  }

  const suffix = segments.at(-1) ?? ''
  return !suffix || (required.endsWith(suffix) && required.length >= cursor + suffix.length)
}

export function hasPermission(granted: string[] | undefined, required: string): boolean {
  return Boolean(granted?.some((permission) => permissionPatternMatches(permission, required)))
}
