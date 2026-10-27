/** Structural subset shared by statuses and boost wrappers. */
export interface StatusRow {
  id: string
  reblog: { id: string } | null
}

/** A deleted original also deletes every boost wrapper displaying it. */
export function statusRowMatches(status: StatusRow, deletedId: string): boolean {
  return status.id === deletedId || status.reblog?.id === deletedId
}

/** Newest timeline wrapper id, independent of the array's display order. */
export function newestWrapperId(statuses: readonly StatusRow[]): string {
  let newest = ''
  for (const status of statuses) {
    if (status.id > newest) newest = status.id
  }
  return newest
}
