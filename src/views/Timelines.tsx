// STUB — replaced by the timelines module (orchestrator dispatch).
import type { Status } from '../api/types'
import { StatusCard } from '../components/StatusCard'

export interface TimelinePageProps {
  kind: 'home' | 'local' | 'federated' | 'tag' | 'list'
  tag?: string
  listId?: string
}

export function TimelinePage(_props: TimelinePageProps) {
  return <StatusCard status={placeholderStatus()} />
}

function placeholderStatus(): Status {
  return {
    id: 'stub', created_at: new Date().toISOString(), in_reply_to_id: null,
    in_reply_to_account_id: null, sensitive: false, spoiler_text: '',
    visibility: 'public', language: 'en', content: '<p>Timeline not built yet.</p>',
    url: null, uri: '', replies_count: 0, reblogs_count: 0, favourites_count: 0,
    favourited: false, reblogged: false, muted: false, bookmarked: false,
    poll: null, media_attachments: [], mentions: [], tags: [], emojis: [],
    application: null,
    account: {
      id: 'stub', username: 'stub', domain: 'stub', acct: 'stub@stub',
      display_name: 'Stub', note: '', url: '', avatar: '/favicon.svg',
      avatar_static: '/favicon.svg', header: '', header_static: '', locked: false,
      emojis: [], discoverable: false, created_at: new Date().toISOString(),
      statuses_count: 0, followers_count: 0, following_count: 0,
    },
    reblog: null,
  }
}
