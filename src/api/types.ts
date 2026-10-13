/** Mastodon-compatible API types as served by GoToSocial 0.22.x. */

export interface Emoji {
  shortcode: string
  url: string
  static_url: string
  visible_in_picker: boolean
  category?: string
}

export interface Instance {
  uri: string
  title: string
  short_description: string
  description: string
  email: string
  version: string
  languages: string[]
  registrations: boolean
  approval_required: boolean
  configuration: {
    statuses: {
      max_characters: number
      max_media_attachments: number
      characters_reserved_per_url: number
      supported_mime_types: string[]
    }
    media_attachments: {
      supported_mime_types: string[]
      image_size_limit: number
      video_size_limit: number
    }
    polls: {
      max_options: number
      max_characters_per_option: number
      min_expiration: number
      max_expiration: number
    }
    accounts: { max_profile_fields: number; max_featured_tags: number }
    emojis: { emoji_size_limit: number }
  }
  urls: { streaming_api: string }
  stats: { domain_count: number; status_count: number; user_count: number }
  thumbnail?: string
  rules: { id: string; text: string }[]
  contact?: { email?: string; account?: Account }
}

export interface AccountField {
  name: string
  value: string
  verified_at?: string | null
}

export interface Account {
  id: string
  username: string
  domain: string
  acct: string
  display_name: string
  note: string
  url: string
  avatar: string
  avatar_static: string
  header: string
  header_static: string
  locked: boolean
  emojis: Emoji[]
  discoverable: boolean
  created_at: string
  statuses_count: number
  followers_count: number
  following_count: number
  fields?: AccountField[]
  moved?: Account | null
  hide_collections?: boolean
  bot?: boolean
}

export interface MediaAttachment {
  id: string
  type: 'image' | 'video' | 'audio' | 'gifv' | 'unknown'
  url: string | null
  preview_url: string | null
  remote_url?: string | null
  meta?: Record<string, unknown>
  description: string | null
  blurhash?: string
}

export interface PollOption { title: string; votes_count?: number }

export interface Poll {
  id: string
  expires_at: string | null
  expired: boolean
  multiple: boolean
  votes_count: number
  voters_count: number | null
  voted?: boolean
  own_votes?: number[]
  options: PollOption[]
  emojis: Emoji[]
}

export interface Mention {
  id: string
  username: string
  url: string
  acct: string
}

export interface Application {
  name: string
  website?: string | null
}

/** GoToSocial interaction policy: who may reply/boost/favourite.
 *  Entries are keywords (public, followers, following, mutuals, mentioned,
 *  author, me) or full ActivityPub actor URIs. */
export interface PolicyRules {
  automatic_approval: string[]
  manual_approval: string[]
}

export interface InteractionPolicy {
  can_favourite?: PolicyRules
  can_reblog?: PolicyRules
  can_reply?: PolicyRules
}

export interface PreviewCard {
  url: string
  title: string
  description: string
  type: 'link' | 'photo' | 'video' | 'rich'
  image: string | null
  provider_name?: string
}

export interface Status {
  id: string
  created_at: string
  edited_at?: string | null
  in_reply_to_id: string | null
  in_reply_to_account_id: string | null
  sensitive: boolean
  spoiler_text: string
  visibility: 'public' | 'unlisted' | 'private' | 'direct' | 'local'
  language: string | null
  text?: string
  content: string
  url: string | null
  uri: string
  replies_count: number
  reblogs_count: number
  favourites_count: number
  favourited: boolean
  reblogged: boolean
  muted: boolean
  bookmarked: boolean
  pinned?: boolean
  content_type?: string
  poll: Poll | null
  media_attachments: MediaAttachment[]
  mentions: Mention[]
  tags: { name: string; url: string }[]
  emojis: Emoji[]
  application: Application | null
  account: Account
  reblog: Status | null
  card?: PreviewCard | null
  interaction_policy?: InteractionPolicy | null
  filtered?: FilterResult[]
}

export interface FilterResult {
  filter: { id: string; title: string; filter_action: 'warn' | 'hide' | 'blur' }
  keyword_matches?: string[]
  status_matches?: string[]
}

export interface Relationship {
  id: string
  following: boolean
  showing_reblogs: boolean
  notifying: boolean
  followed_by: boolean
  blocking: boolean
  blocked_by: boolean
  muting: boolean
  muting_notifications: boolean
  requested: boolean
  domain_blocking: boolean
  endorsed: boolean
  note: string
}

export interface Notification {
  id: string
  type:
    | 'mention' | 'follow' | 'favourite' | 'reblog' | 'poll'
    | 'follow_request' | 'status' | 'update' | 'admin.sign_up' | 'admin.report'
  created_at: string
  status: Status | null
  account?: Account
}

export interface List {
  id: string
  title: string
  replies_policy?: string
  exclusive?: boolean
}

export interface Marker {
  last_read_id: string
  version: number
  updated_at: string
}

export interface SearchResults {
  accounts: Account[]
  statuses: Status[]
  hashtags: { name: string; url?: string; history?: unknown[] }[]
}

export interface TokenResponse {
  access_token: string
  token_type: string
  scope: string
  created_at: number
}

export interface AccountCredentials extends Account {
  source: { note: string; fields: AccountField[]; privacy?: string; sensitive?: boolean; language?: string }
  role?: { name?: string; permissions?: string | number }
}

export interface TagInfo {
  name: string
  url?: string
  history?: unknown[]
  following?: boolean
}

export interface StatusEdit {
  content: string
  spoiler_text: string
  sensitive: boolean
  created_at: string
  account: Account
  media_attachments: MediaAttachment[]
  emojis: Emoji[]
}
