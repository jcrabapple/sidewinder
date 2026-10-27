/**
 * Global compose dialog: new posts, replies, edits. Supports media uploads
 * with alt text, polls, content warnings, visibility, and a custom-emoji
 * picker. Opened via openCompose() from state/compose.ts.
 */
import { useEffect, useRef, useState } from 'preact/hooks'
import { composeOpen, composeContext, closeCompose, lastPosted } from '../state/compose'
import { saveDraft, loadDraft, clearDraft, type ComposeDraft, type DraftMedia } from '../state/draft'
import { advancePublication, ensureIdempotencyKeys, mergePresetText, nextPublicationIndex, withMediaIds, type PublicationProgress } from '../state/composePublish'
import { api, activeSession } from '../state/auth'
import { NetworkError } from '../api/client'
import { settings } from '../state/settings'
import { toast } from '../state/toast'
import { customEmojis, emojisStatus, ensureEmojis, loadEmojiSet, pickerEmojis, filterPickerEmojis, groupPickerEmojis, type PickerEmoji } from '../state/emojis'
import type { Account, Instance, MediaAttachment, SearchResults, Status } from '../api/types'
import { Icon } from '../components/Icon'
import './Compose.css'

type Visibility = 'public' | 'unlisted' | 'private' | 'direct'

/** Presets for the per-post interaction policy (GoToSocial extension). */
type PolicyChoice = 'default' | 'public' | 'followers' | 'mentioned' | 'following' | 'nobody' | 'approval'

const POLICY_OPTIONS: { value: PolicyChoice; label: string }[] = [
  { value: 'default', label: 'Default' },
  { value: 'public', label: 'Anyone' },
  { value: 'followers', label: 'Followers' },
  { value: 'following', label: 'People I follow' },
  { value: 'mentioned', label: 'Mentioned only' },
  { value: 'approval', label: 'With my approval' },
  { value: 'nobody', label: 'Nobody' },
]

function rulesFor(choice: PolicyChoice): { automatic_approval: string[]; manual_approval: string[] } | null {
  switch (choice) {
    case 'public': return { automatic_approval: ['public'], manual_approval: [] }
    case 'followers': return { automatic_approval: ['followers'], manual_approval: [] }
    case 'following': return { automatic_approval: ['following'], manual_approval: [] }
    case 'mentioned': return { automatic_approval: ['mentioned'], manual_approval: [] }
    // Nobody but the author (mentions the server auto-adds stay allowed).
    case 'nobody': return { automatic_approval: ['author'], manual_approval: [] }
    // Everyone else must send a request the author approves manually.
    case 'approval': return { automatic_approval: ['author'], manual_approval: ['public'] }
    default: return null
  }
}

interface Limits {
  maxChars: number
  maxMedia: number
  pollMaxOptions: number
  pollMaxChars: number
  pollMinExp: number
  pollMaxExp: number
}

const DEFAULT_LIMITS: Limits = {
  maxChars: 5000,
  maxMedia: 6,
  pollMaxOptions: 6,
  pollMaxChars: 50,
  pollMinExp: 300,
  pollMaxExp: 2629746,
}

const limitsCache = new Map<string, Limits>()
const MEDIA_REQUEST_TIMEOUT_MS = 120_000

async function fetchLimits(): Promise<Limits> {
  const base = api.value.base
  const cached = limitsCache.get(base)
  if (cached) return cached
  try {
    const inst = await api.value.get<Instance>('/api/v1/instance')
    const c = inst.configuration
    const limits: Limits = {
      maxChars: c?.statuses?.max_characters ?? DEFAULT_LIMITS.maxChars,
      maxMedia: c?.statuses?.max_media_attachments ?? DEFAULT_LIMITS.maxMedia,
      pollMaxOptions: c?.polls?.max_options ?? DEFAULT_LIMITS.pollMaxOptions,
      pollMaxChars: c?.polls?.max_characters_per_option ?? DEFAULT_LIMITS.pollMaxChars,
      pollMinExp: c?.polls?.min_expiration ?? DEFAULT_LIMITS.pollMinExp,
      pollMaxExp: c?.polls?.max_expiration ?? DEFAULT_LIMITS.pollMaxExp,
    }
    limitsCache.set(base, limits)
    return limits
  } catch {
    return DEFAULT_LIMITS
  }
}

type SuggestItem =
  | { kind: 'account'; key: string; acct: string; name: string; avatar: string; insert: string }
  | { kind: 'tag'; key: string; name: string; insert: string }
  | { kind: 'emoji'; key: string; label: string; char?: string; url?: string; insert: string }

interface ActiveToken {
  trigger: '@' | '#' | ':'
  query: string
  start: number
  end: number
}

function detectToken(value: string, cursor: number): ActiveToken | null {
  const before = value.slice(0, cursor)
  const m = before.match(/(?:^|\s)([@#:])([a-zA-Z0-9_.-]{1,32})$/)
  if (!m) return null
  const trigger = m[1] as '@' | '#' | ':'
  const query = m[2]
  if (trigger === ':' && query.length < 2) return null
  const start = cursor - query.length - 1
  return { trigger, query, start, end: cursor }
}

const VISIBILITY_LABELS: Record<Visibility, string> = {
  public: 'Public — anyone can see',
  unlisted: 'Unlisted — public, but not listed',
  private: 'Followers only',
  direct: 'Direct — mentioned people only',
}

const EXPIRY_OPTIONS: [number, string][] = [
  [300, '5 minutes'],
  [1800, '30 minutes'],
  [3600, '1 hour'],
  [21600, '6 hours'],
  [86400, '1 day'],
  [259200, '3 days'],
  [604800, '7 days'],
]

/** Strip HTML tags as a fallback source text for edits. */
function stripTags(html: string): string {
  // <template> content is inert: no scripts, no resource loads.
  const el = document.createElement('template')
  el.innerHTML = html
  return el.content.textContent ?? ''
}

export function ComposeHost() {
  const open = composeOpen.value
  const ctx = composeContext.value
  const sessionId = activeSession.value?.id ?? ''
  const dlgRef = useRef<HTMLDialogElement>(null)
  const textRef = useRef<HTMLTextAreaElement>(null)
  const fileRef = useRef<HTMLInputElement>(null)
  const lastFocus = useRef<Element | null>(null)
  const uploadGeneration = useRef(0)
  const openRef = useRef(open)
  const discardDraft = useRef(false)
  const flushDraftRef = useRef<() => void>(() => {})
  openRef.current = open

  const [text, setText] = useState('')
  const [cw, setCw] = useState(false)
  const [spoiler, setSpoiler] = useState('')
  const [visibility, setVisibility] = useState<Status['visibility']>(settings.value.defaultVisibility)
  const [media, setMedia] = useState<DraftMedia[]>([])
  const [pollOn, setPollOn] = useState(false)
  const [pollOptions, setPollOptions] = useState<string[]>(['', ''])
  const [pollMultiple, setPollMultiple] = useState(false)
  const [pollExpiry, setPollExpiry] = useState(86400)
  const [limits, setLimits] = useState<Limits>(DEFAULT_LIMITS)
  const [pickerOpen, setPickerOpen] = useState(false)
  const [pickerQuery, setPickerQuery] = useState('')
  const [policyOpen, setPolicyOpen] = useState(false)
  const [replyPolicy, setReplyPolicy] = useState<PolicyChoice>('default')
  const [boostPolicy, setBoostPolicy] = useState<PolicyChoice>('default')
  const [favPolicy, setFavPolicy] = useState<PolicyChoice>('default')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [altWarned, setAltWarned] = useState(false)
  const [dragging, setDragging] = useState(false)
  const [chain, setChain] = useState<string[]>([])
  const [idempotencyKeys, setIdempotencyKeys] = useState<string[]>([])
  const [publishedCount, setPublishedCount] = useState(0)
  const [publishedParentId, setPublishedParentId] = useState<string | undefined>(undefined)
  const [suggest, setSuggest] = useState<SuggestItem[]>([])
  const [suggestIdx, setSuggestIdx] = useState(0)
  const [token, setToken] = useState<ActiveToken | null>(null)
  const suggestSeq = useRef(0)

  const editing = ctx?.editing
  const replyTo = ctx?.replyTo

  const makeDraft = (progress: PublicationProgress = { publishedCount, publishedParentId }, keys = idempotencyKeys): ComposeDraft => ({
    text,
    cw,
    spoiler: cw ? spoiler : '',
    visibility,
    media,
    pollOn,
    pollOptions,
    pollMultiple,
    pollExpiry,
    chain,
    idempotencyKeys: keys,
    publishedCount: progress.publishedCount,
    publishedParentId: progress.publishedParentId,
    savedAt: Date.now(),
  })

  flushDraftRef.current = () => {
    if (!sessionId || editing || replyTo || discardDraft.current) return
    const hasDraftContent = text.trim().length > 0 || media.length > 0 || chain.some((part) => part.trim().length > 0)
      || (pollOn && pollOptions.some((option) => option.trim())) || publishedCount > 0
    if (hasDraftContent) saveDraft(sessionId, makeDraft())
    else clearDraft(sessionId)
  }

  // Debounced autosave plus a synchronous close/unmount flush covers both
  // crashes and dismissal inside the debounce window.
  useEffect(() => {
    if (!open || !sessionId || editing || replyTo) return
    const timer = setTimeout(() => flushDraftRef.current(), 400)
    return () => clearTimeout(timer)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, sessionId, editing, replyTo, text, cw, spoiler, visibility, media, pollOn, pollOptions, pollMultiple, pollExpiry, chain, idempotencyKeys, publishedCount, publishedParentId])

  useEffect(() => () => {
    uploadGeneration.current += 1
    flushDraftRef.current()
  }, [])

  // Sync native <dialog> with the composeOpen signal.
  useEffect(() => {
    const d = dlgRef.current
    if (!d) return
    if (open && !d.open) {
      uploadGeneration.current += 1
      lastFocus.current = document.activeElement
      d.showModal()
    } else if (!open && d.open) {
      flushDraftRef.current()
      uploadGeneration.current += 1
      d.close()
    }
  }, [open])

  // Reset the draft whenever the dialog opens with a new context.
  useEffect(() => {
    if (!open) return
    discardDraft.current = false
    setError(null)
    setAltWarned(false)
    setBusy(false)
    setPickerOpen(false)
    setPickerQuery('')
    setMedia([])
    setPollOn(false)
    setPollOptions(['', ''])
    setPollMultiple(false)
    setPolicyOpen(false)
    setReplyPolicy('default')
    setBoostPolicy('default')
    setFavPolicy('default')
    setChain([])
    setIdempotencyKeys([])
    setPublishedCount(0)
    setPublishedParentId(undefined)
    setSuggest([])
    setToken(null)
    if (editing) {
      setText(editing.text ?? stripTags(editing.content))
      setSpoiler(editing.spoiler_text)
      setCw(Boolean(editing.spoiler_text))
      setMedia(editing.media_attachments.map((attachment) => ({ attachment, uploading: false })))
    } else {
      const prefix = replyTo ? `@${replyTo.account.acct} ` : ''
      setText(prefix + (ctx?.presetText ?? ''))
      setSpoiler('')
      setCw(false)
    }
    setVisibility(
      editing
        ? editing.visibility
        : replyTo && replyTo.visibility !== 'local'
          ? replyTo.visibility
          : settings.value.defaultVisibility,
    )
    // Draft recovery: a fresh new-post composer restores the saved draft.
    if (!editing && !replyTo) {
      const saved = loadDraft(sessionId)
      if (saved && (saved.text.trim() || saved.media.length > 0 || saved.pollOn || saved.chain?.some((part) => part.trim()))) {
        const resumeCount = saved.publishedCount ?? 0
        const sharedText = ctx?.presetText
        setText(resumeCount > 0 ? saved.text : mergePresetText(saved.text, sharedText))
        setCw(saved.cw)
        setSpoiler(saved.spoiler)
        setVisibility(saved.visibility as Status['visibility'])
        setMedia(saved.media)
        setPollOn(saved.pollOn)
        setPollOptions(saved.pollOptions)
        setPollMultiple(saved.pollMultiple)
        setPollExpiry(saved.pollExpiry)
        setChain(resumeCount > 0 && sharedText?.trim() && !saved.chain?.includes(sharedText) && saved.text !== sharedText
          ? [...(saved.chain ?? []), sharedText]
          : saved.chain ?? [])
        setIdempotencyKeys(saved.idempotencyKeys ?? [])
        setPublishedCount(resumeCount)
        setPublishedParentId(saved.publishedParentId)
        toast('Draft restored')
      }
    }
    void fetchLimits().then(setLimits)
    void ensureEmojis()
    void loadEmojiSet()
    requestAnimationFrame(() => {
      const ta = textRef.current
      if (ta) {
        ta.focus()
        ta.selectionStart = ta.selectionEnd = ta.value.length
      }
    })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open])

  function close() {
    flushDraftRef.current()
    uploadGeneration.current += 1
    openRef.current = false
    closeCompose()
    const el = lastFocus.current
    if (el instanceof HTMLElement) el.focus()
  }

  function insertSnippet(snippet: string) {
    const ta = textRef.current
    if (!ta) {
      setText((t) => t + snippet)
      return
    }
    const start = ta.selectionStart ?? ta.value.length
    const end = ta.selectionEnd ?? start
    setText(ta.value.slice(0, start) + snippet + ta.value.slice(end))
    requestAnimationFrame(() => {
      ta.focus()
      ta.selectionStart = ta.selectionEnd = start + snippet.length
    })
  }

  function updateTokenFromTextarea(ta: HTMLTextAreaElement) {
    const cur = ta.selectionStart ?? ta.value.length
    const detected = detectToken(ta.value, cur)
    setToken(detected)
    if (!detected) setSuggest([])
  }

  useEffect(() => {
    if (!token) {
      setSuggest([])
      return
    }
    const seq = ++suggestSeq.current
    const { trigger, query } = token
    if (trigger === ':') {
      const matches = filterPickerEmojis(pickerEmojis(), query).slice(0, 8)
      setSuggest(
        matches.map((e) => ({
          kind: 'emoji',
          key: e.key,
          label: e.shortcode ? `:${e.shortcode}:` : e.name,
          char: e.char,
          url: e.url,
          insert: (e.char ?? `:${e.shortcode}:`) + ' ',
        })),
      )
      setSuggestIdx(0)
      return
    }
    const timer = setTimeout(() => {
      if (trigger === '@') {
        api.value
          .get<Account[]>('/api/v1/accounts/search', { q: query, limit: 6 })
          .then((list) => {
            if (seq !== suggestSeq.current) return
            setSuggest(
              (list ?? []).slice(0, 6).map((a) => ({
                kind: 'account',
                key: a.id,
                acct: a.acct,
                name: a.display_name || a.username,
                avatar: a.avatar_static || a.avatar,
                insert: `@${a.acct} `,
              })),
            )
            setSuggestIdx(0)
          })
          .catch(() => {
            if (seq === suggestSeq.current) setSuggest([])
          })
      } else if (trigger === '#') {
        api.value
          .get<SearchResults>('/api/v2/search', { q: query, type: 'hashtags', limit: 6 })
          .then((res) => {
            if (seq !== suggestSeq.current) return
            setSuggest(
              (res.hashtags ?? []).slice(0, 6).map((h) => ({
                kind: 'tag',
                key: h.name,
                name: h.name,
                insert: `#${h.name} `,
              })),
            )
            setSuggestIdx(0)
          })
          .catch(() => {
            if (seq === suggestSeq.current) setSuggest([])
          })
      }
    }, 180)
    return () => clearTimeout(timer)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [token?.trigger, token?.query])

  function applySuggestion(item: SuggestItem) {
    if (!token) return
    const nextText = text.slice(0, token.start) + item.insert + text.slice(token.end)
    const nextCursor = token.start + item.insert.length
    setText(nextText)
    setSuggest([])
    setToken(null)
    requestAnimationFrame(() => {
      const ta = textRef.current
      if (ta) {
        ta.focus()
        ta.selectionStart = ta.selectionEnd = nextCursor
      }
    })
  }

  async function addFiles(files: FileList | File[] | null) {
    if (!files || busy || pollOn || publishedCount > 0) return
    const generation = uploadGeneration.current
    const uploadSessionId = sessionId
    const uploadIsCurrent = () => openRef.current && generation === uploadGeneration.current && activeSession.value?.id === uploadSessionId
    setError(null)
    const room = limits.maxMedia - media.length
    const picks = [...files].slice(0, Math.max(0, room))
    if (picks.length < files.length) setError(`This server allows at most ${limits.maxMedia} attachments.`)
    for (const file of picks) {
      const fd = new FormData()
      fd.append('file', file)
      const placeholder: DraftMedia = {
        uploading: true,
        attachment: { id: `pending-${Math.random().toString(36).slice(2)}`, type: 'unknown', url: null, preview_url: null, description: null },
      }
      setMedia((items) => [...items, placeholder])
      try {
        let att = await api.value.request<MediaAttachment>('/api/v2/media', {
          method: 'POST',
          body: fd,
          timeoutMs: MEDIA_REQUEST_TIMEOUT_MS,
        })
        // 202 processing: poll until the URL materializes.
        for (let i = 0; !att.url && i < 12; i++) {
          await new Promise((resolve) => setTimeout(resolve, 1500))
          if (!uploadIsCurrent()) return
          att = await api.value.request<MediaAttachment>(`/api/v1/media/${att.id}`, { timeoutMs: MEDIA_REQUEST_TIMEOUT_MS })
        }
        if (!uploadIsCurrent()) return
        if (!att.url) throw new Error('The server is still processing that file. Wait a moment, then attach it and try again.')
        setMedia((items) => items.map((item) => item.attachment.id === placeholder.attachment.id ? { attachment: att, uploading: false } : item))
      } catch (e) {
        if (!uploadIsCurrent()) return
        setMedia((items) => items.filter((item) => item.attachment.id !== placeholder.attachment.id))
        const networkMessage = e instanceof NetworkError ? `${e.message} ${e.cause instanceof Error ? e.cause.message : ''}` : ''
        setError(/abort|timeout|timed out/i.test(networkMessage)
          ? 'Media upload timed out. Check your connection, then attach the file and try again.'
          : e instanceof Error ? e.message : 'Upload failed. Try a different file.')
      }
    }
  }

  function removeMedia(id: string) {
    if (publishedCount > 0) return
    setMedia((m) => m.filter((d) => d.attachment.id !== id))
  }

  async function saveAlt(id: string, description: string) {
    if (publishedCount > 0) return
    setMedia((m) => m.map((d) => (d.attachment.id === id ? { ...d, attachment: { ...d.attachment, description } } : d)))
    if (id.startsWith('pending-')) return
    try {
      const att = await api.value.put<MediaAttachment>(`/api/v1/media/${id}`, { description })
      setMedia((m) => m.map((d) => (d.attachment.id === id ? { ...d, attachment: att } : d)))
    } catch {
      toast('Could not save the image description', 'error')
    }
  }

  const usedPollOptions = pollOptions.map((o) => o.trim()).filter(Boolean)
  const overLimit = text.length + (cw ? spoiler.length : 0) > limits.maxChars
  const chainValid = chain.every((c) => c.trim().length > 0 && c.length + (cw ? spoiler.length : 0) <= limits.maxChars)
  const uploading = media.some((d) => d.uploading)
  const missingAlt = media.some((d) => !d.uploading && !d.attachment.description?.trim())
  const pollValid = !pollOn || (usedPollOptions.length >= 2 && usedPollOptions.every((o) => o.length <= limits.pollMaxChars))
  const hasContent = text.trim().length > 0 || media.length > 0 || publishedCount > 0
  const canSubmit = hasContent && !overLimit && chainValid && !uploading && pollValid && !busy
    && publishedCount < chain.length + 1

  async function submit() {
    if (!canSubmit) return
    if (missingAlt && settings.value.requireAltText && !altWarned) {
      setAltWarned(true)
      setError('Some attachments have no description. Press post again to publish anyway.')
      return
    }
    setBusy(true)
    setError(null)
    const baseBody: Record<string, unknown> = {
      status: text,
      sensitive: cw,
      spoiler_text: cw ? spoiler : '',
      visibility,
      content_type: 'text/markdown',
    }
    if (replyTo) baseBody.in_reply_to_id = replyTo.id
    let policyPayload: Record<string, unknown> | undefined
    if (!editing && (replyPolicy !== 'default' || boostPolicy !== 'default' || favPolicy !== 'default')) {
      // If ANY policy is set, GTS normalizes unspecified sub-policies to
      // restrictive. Preserve intent by explicitly sending public for the
      // untouched types.
      const ip: Record<string, unknown> = {}
      const r = replyPolicy !== 'default' ? rulesFor(replyPolicy) : { automatic_approval: ['public'], manual_approval: [] }
      const b = boostPolicy !== 'default' ? rulesFor(boostPolicy) : { automatic_approval: ['public'], manual_approval: [] }
      const f = favPolicy !== 'default' ? rulesFor(favPolicy) : { automatic_approval: ['public'], manual_approval: [] }
      if (r) ip.can_reply = r
      if (b) ip.can_reblog = b
      if (f) ip.can_favourite = f
      baseBody.interaction_policy = ip
      policyPayload = ip
    }
    if (pollOn && !editing) {
      baseBody.poll = {
        options: usedPollOptions,
        expires_in: Math.min(Math.max(pollExpiry, limits.pollMinExp), limits.pollMaxExp),
        multiple: pollMultiple,
      }
    }
    const body = withMediaIds(baseBody, media, Boolean(editing))
    try {
      if (editing) {
        await api.value.put<Status>(`/api/v1/statuses/${editing.id}`, body)
        toast('Post updated')
      } else {
        const segments = [text, ...chain]
        const keys = ensureIdempotencyKeys(idempotencyKeys, segments.length)
        setIdempotencyKeys(keys)
        let progress: PublicationProgress = { publishedCount, publishedParentId }
        let parentId = publishedParentId
        let created: Status | null = null
        if (!replyTo && sessionId) saveDraft(sessionId, makeDraft(progress, keys))
        for (let index = nextPublicationIndex(progress, segments.length); index !== null; index = nextPublicationIndex(progress, segments.length)) {
          let segmentBody = body
          if (index > 0) {
            if (!parentId) throw new Error('The thread could not resume because its published parent is missing. Restore the draft or contact support.')
            segmentBody = {
              status: segments[index].trim(),
              sensitive: cw,
              spoiler_text: cw ? spoiler : '',
              visibility,
              content_type: 'text/markdown',
              in_reply_to_id: parentId,
            }
            if (policyPayload) segmentBody.interaction_policy = policyPayload
          }
          created = await api.value.request<Status>('/api/v1/statuses', {
            method: 'POST',
            body: segmentBody,
            headers: { 'Idempotency-Key': keys[index] },
          })
          parentId = created.id
          progress = advancePublication(progress, created.id)
          setPublishedCount(progress.publishedCount)
          setPublishedParentId(progress.publishedParentId)
          if (!replyTo && sessionId) saveDraft(sessionId, makeDraft(progress, keys))
        }
        if (!replyTo && sessionId) {
          discardDraft.current = true
          clearDraft(sessionId)
        }
        if (created) lastPosted.value = created
        toast(chain.length > 0 ? `Thread posted (${chain.length + 1} posts)` : 'Posted')
      }
      close()
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not publish. Check your connection and try again.')
    } finally {
      setBusy(false)
    }
  }

  const title = editing ? 'Edit post' : replyTo ? `Reply to @${replyTo.account.acct}` : 'New post'
  const pickerItems = filterPickerEmojis(pickerEmojis(), pickerQuery)
  const emojiGroups = groupPickerEmojis(pickerItems)

  return (
    <dialog
      ref={dlgRef}
      class={`sw-dialog compose${dragging ? ' compose--dragging' : ''}`}
      aria-labelledby="compose-title"
      onClose={() => {
        flushDraftRef.current()
        if (composeOpen.value) closeCompose()
      }}
      onCancel={() => {
        if (composeOpen.value) close()
      }}
      onDragOver={(e) => {
        if (e.dataTransfer?.types.includes('Files')) {
          e.preventDefault()
          if (!dragging) setDragging(true)
        }
      }}
      onDragLeave={(e) => {
        if (e.currentTarget === e.target || !e.currentTarget.contains(e.relatedTarget as Node)) {
          setDragging(false)
        }
      }}
      onDrop={(e) => {
        setDragging(false)
        const files = e.dataTransfer?.files
        if (files && files.length > 0) {
          e.preventDefault()
          void addFiles(files)
        }
      }}
    >
      <div class="sw-dialog__head">
        <h2 id="compose-title">{title}</h2>
        <button class="icon-btn" style="margin-left:auto" onClick={close} aria-label="Close compose" title="Close">
          <Icon name="x" size={20} />
        </button>
      </div>
      <div class="sw-dialog__body compose__body">
        <form
          onSubmit={(e) => {
            e.preventDefault()
            void submit()
          }}
        >
          {cw && (
            <input
              class="input compose__cw"
              value={spoiler}
              onInput={(e) => setSpoiler((e.target as HTMLInputElement).value)}
              placeholder="Content warning"
              aria-label="Content warning"
              maxLength={limits.maxChars}
            />
          )}
          <textarea
            ref={textRef}
            class="textarea compose__text"
            value={text}
            onInput={(e) => {
              const ta = e.target as HTMLTextAreaElement
              setText(ta.value)
              updateTokenFromTextarea(ta)
            }}
            onClick={(e) => updateTokenFromTextarea(e.target as HTMLTextAreaElement)}
            onPaste={(e) => {
              const items = e.clipboardData?.items
              if (!items) return
              const files: File[] = []
              for (const item of items) {
                if (item.kind === 'file' && /^(image|video|audio)\//.test(item.type)) {
                  const f = item.getAsFile()
                  if (f) files.push(f)
                }
              }
              if (files.length > 0) {
                e.preventDefault()
                void addFiles(files)
              }
            }}
            onKeyDown={(e) => {
              if (suggest.length > 0) {
                if (e.key === 'ArrowDown') {
                  e.preventDefault()
                  setSuggestIdx((i) => (i + 1) % suggest.length)
                  return
                }
                if (e.key === 'ArrowUp') {
                  e.preventDefault()
                  setSuggestIdx((i) => (i - 1 + suggest.length) % suggest.length)
                  return
                }
                if (e.key === 'Enter' || e.key === 'Tab') {
                  e.preventDefault()
                  applySuggestion(suggest[suggestIdx] ?? suggest[0])
                  return
                }
                if (e.key === 'Escape') {
                  e.preventDefault()
                  setSuggest([])
                  setToken(null)
                  return
                }
              }
              if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') {
                e.preventDefault()
                void submit()
              }
            }}
            placeholder={replyTo ? 'Write your reply' : "What's on your mind?"}
            aria-label="Post text"
            disabled={publishedCount > 0}
            rows={6}
            aria-busy={busy || undefined}
          />
          {suggest.length > 0 && (
            <ul class="compose__suggest" role="listbox" aria-label="Autocomplete suggestions">
              {suggest.map((item, idx) => (
                <li key={item.key}>
                  <button
                    type="button"
                    role="option"
                    aria-selected={idx === suggestIdx}
                    class={`compose__suggest-item${idx === suggestIdx ? ' is-active' : ''}`}
                    onMouseDown={(e) => {
                      e.preventDefault()
                      applySuggestion(item)
                    }}
                  >
                    {item.kind === 'account' && (
                      <>
                        <img src={item.avatar} alt="" width={24} height={24} loading="lazy" />
                        <span class="compose__suggest-primary">{item.name}</span>
                        <span class="compose__suggest-secondary">@{item.acct}</span>
                      </>
                    )}
                    {item.kind === 'tag' && (
                      <>
                        <Icon name="hash" size={16} />
                        <span class="compose__suggest-primary">#{item.name}</span>
                      </>
                    )}
                    {item.kind === 'emoji' && (
                      <>
                        {item.url ? (
                          <img src={item.url} alt="" width={20} height={20} loading="lazy" />
                        ) : (
                          <span class="compose__emoji-char">{item.char}</span>
                        )}
                        <span class="compose__suggest-primary">{item.label}</span>
                      </>
                    )}
                  </button>
                </li>
              ))}
            </ul>
          )}
          <div class="compose__meta">
            <span class={`compose__count num${overLimit ? ' compose__count--over' : ''}`} aria-live="polite">
              {text.length + (cw ? spoiler.length : 0)} / {limits.maxChars}
            </span>
          </div>

          {!editing && chain.length > 0 && (
            <div class="compose__chain" aria-label="Chained thread posts">
              {chain.map((part, idx) => {
                const partOver = part.length + (cw ? spoiler.length : 0) > limits.maxChars
                return (
                  <div key={idx} class="compose__chain-item">
                    <div class="compose__chain-head">
                      <span class="compose__chain-label num">Post {idx + 2}</span>
                      <span class={`compose__count num${partOver ? ' compose__count--over' : ''}`}>
                        {part.length + (cw ? spoiler.length : 0)} / {limits.maxChars}
                      </span>
                      <button
                        type="button"
                        class="icon-btn compose__chain-remove"
                        aria-label={`Remove post ${idx + 2}`}
                        title={`Remove post ${idx + 2}`}
                        disabled={idx < publishedCount - 1}
                        onClick={() => {
                          setChain((parts) => parts.filter((_, partIndex) => partIndex !== idx))
                          setIdempotencyKeys((keys) => keys.filter((_, keyIndex) => keyIndex !== idx + 1))
                        }}
                      >
                        <Icon name="x" size={16} />
                      </button>
                    </div>
                    <textarea
                      class="textarea compose__text compose__chain-text"
                      rows={4}
                      value={part}
                      disabled={idx < publishedCount - 1}
                      placeholder={`Continue thread (post ${idx + 2})`}
                      aria-label={`Post ${idx + 2} text${idx < publishedCount - 1 ? ' (published)' : ''}`}
                      onInput={(e) => {
                        const val = (e.target as HTMLTextAreaElement).value
                        setChain((c) => c.map((v, j) => (j === idx ? val : v)))
                      }}
                    />
                  </div>
                )
              })}
            </div>
          )}

          {media.length > 0 && (
            <ul class="compose__media">
              {media.map((d) => (
                <li key={d.attachment.id} class="compose__media-item">
                  {d.uploading ? (
                    <div class="compose__media-pending" role="status" aria-label="Uploading attachment">
                      <span class="spinner" />
                    </div>
                  ) : (
                    <>
                      {d.attachment.preview_url || d.attachment.url ? (
                        <img
                          class="compose__media-thumb"
                          src={d.attachment.preview_url ?? d.attachment.url ?? ''}
                          alt={d.attachment.description ?? ''}
                        />
                      ) : (
                        <span class="compose__media-file">{d.attachment.type}</span>
                      )}
                      <label class="compose__alt">
                        <span class="visually-hidden">Image description</span>
                        <input
                          class="input"
                          defaultValue={d.attachment.description ?? ''}
                          placeholder="Describe this media (alt text)"
                          disabled={publishedCount > 0}
                          onChange={(e) => void saveAlt(d.attachment.id, (e.target as HTMLInputElement).value)}
                        />
                      </label>
                      <button
                        type="button"
                        class="icon-btn compose__media-remove"
                        disabled={publishedCount > 0}
                        onClick={() => removeMedia(d.attachment.id)}
                        aria-label="Remove attachment"
                        title="Remove attachment"
                      >
                        <Icon name="x" size={16} />
                      </button>
                    </>
                  )}
                </li>
              ))}
            </ul>
          )}

          {pollOn && !editing && (
            <fieldset class="compose__poll" disabled={publishedCount > 0}>
              <legend class="visually-hidden">Poll options</legend>
              {pollOptions.map((opt, i) => (
                <div class="compose__poll-row" key={i}>
                  <input
                    class="input"
                    value={opt}
                    maxLength={limits.pollMaxChars}
                    placeholder={`Option ${i + 1}`}
                    aria-label={`Poll option ${i + 1}`}
                    onInput={(e) => setPollOptions((o) => o.map((v, j) => (j === i ? (e.target as HTMLInputElement).value : v)))}
                  />
                  {pollOptions.length > 2 && (
                    <button
                      type="button"
                      class="icon-btn"
                      onClick={() => setPollOptions((o) => o.filter((_, j) => j !== i))}
                      aria-label={`Remove option ${i + 1}`}
                      title={`Remove option ${i + 1}`}
                    >
                      <Icon name="x" size={16} />
                    </button>
                  )}
                </div>
              ))}
              <div class="compose__poll-controls">
                <button
                  type="button"
                  class="btn btn--ghost"
                  disabled={pollOptions.length >= limits.pollMaxOptions}
                  onClick={() => setPollOptions((o) => [...o, ''])}
                >
                  <Icon name="plus" size={16} /> Add option
                </button>
                <label class="compose__poll-toggle">
                  <input type="checkbox" checked={pollMultiple} onChange={(e) => setPollMultiple((e.target as HTMLInputElement).checked)} />
                  Multiple choice
                </label>
                <label class="compose__poll-expiry">
                  <span class="visually-hidden">Poll duration</span>
                  <select class="select" value={pollExpiry} onChange={(e) => setPollExpiry(Number((e.target as HTMLSelectElement).value))}>
                    {EXPIRY_OPTIONS.filter(([s]) => s >= limits.pollMinExp && s <= limits.pollMaxExp).map(([s, label]) => (
                      <option key={s} value={s}>{label}</option>
                    ))}
                  </select>
                </label>
              </div>
            </fieldset>
          )}

          {error && (
            <p class="error-note" role="alert">
              {error}
            </p>
          )}

          <div class="compose__toolbar">
            <input
              ref={fileRef}
              type="file"
              class="visually-hidden"
              accept="image/*,video/*,audio/*"
              multiple
              onChange={(e) => {
                void addFiles((e.target as HTMLInputElement).files)
                ;(e.target as HTMLInputElement).value = ''
              }}
            />
            <button
              type="button"
              class="icon-btn"
              onClick={() => fileRef.current?.click()}
              disabled={publishedCount > 0 || pollOn || media.length >= limits.maxMedia || uploading}
              aria-label="Attach media"
              title={pollOn ? 'Polls cannot have media' : 'Attach media'}
            >
              <Icon name="image" size={20} />
            </button>
            <button
              type="button"
              class="icon-btn"
              data-active={pollOn || undefined}
              onClick={() => setPollOn((v) => !v)}
              disabled={publishedCount > 0 || Boolean(editing) || media.length > 0}
              aria-label="Toggle poll"
              title={editing ? 'Polls cannot be edited' : media.length > 0 ? 'Remove media to add a poll' : 'Add a poll'}
            >
              <Icon name="poll" size={20} />
            </button>
            <button
              type="button"
              class="icon-btn"
              data-active={cw || undefined}
              onClick={() => setCw((v) => !v)}
              aria-label="Toggle content warning"
              title="Content warning"
            >
              <Icon name="alert" size={20} />
            </button>
            <button
              type="button"
              class="icon-btn"
              data-active={pickerOpen || undefined}
              onClick={() => setPickerOpen((v) => !v)}
              aria-label="Insert emoji"
              title="Insert emoji"
              aria-expanded={pickerOpen}
            >
              <Icon name="smile" size={20} />
            </button>
            {!editing && (
              <button
                type="button"
                class="icon-btn"
                data-active={policyOpen || undefined}
                onClick={() => setPolicyOpen((v) => !v)}
                aria-label="Who can interact"
                title="Who can interact"
                aria-expanded={policyOpen}
              >
                <Icon name="sliders" size={20} />
              </button>
            )}
            {!editing && chain.length < 9 && (
              <button
                type="button"
                class="icon-btn"
                data-active={chain.length > 0 || undefined}
                onClick={() => setChain((c) => [...c, ''])}
                aria-label="Add post to thread"
                title="Add post to thread"
              >
                <Icon name="plus" size={20} />
              </button>
            )}
            <label class="compose__visibility">
              <span class="visually-hidden">Visibility</span>
              <select
                class="select"
                value={visibility}
                disabled={Boolean(editing)}
                onChange={(e) => setVisibility((e.target as HTMLSelectElement).value as Visibility)}
              >
                {(Object.keys(VISIBILITY_LABELS) as Visibility[]).map((v) => (
                  <option key={v} value={v}>{VISIBILITY_LABELS[v]}</option>
                ))}
                {visibility === 'local' && <option value="local">Local to this instance</option>}
              </select>
            </label>
            <button type="submit" class="btn btn--primary compose__submit" disabled={!canSubmit}>
              {busy
                ? 'Publishing…'
                : editing
                  ? 'Save changes'
                  : publishedCount > 0
                    ? `Resume thread (${chain.length + 1 - publishedCount} left)`
                    : chain.length > 0
                      ? `Post thread (${chain.length + 1})`
                      : replyTo
                        ? 'Reply'
                        : 'Post'}
            </button>
          </div>

          {policyOpen && (
            <div class="compose__policy" role="region" aria-label="Who can interact">
              <p class="compose__policy-hint">Who can interact with this post? (GoToSocial extension)</p>
              <div class="compose__policy-grid">
                <label class="compose__policy-row">
                  <span>Can reply</span>
                  <select class="select" value={replyPolicy} onChange={(e) => setReplyPolicy((e.target as HTMLSelectElement).value as PolicyChoice)}>
                    {POLICY_OPTIONS.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
                  </select>
                </label>
                <label class="compose__policy-row">
                  <span>Can boost</span>
                  <select class="select" value={boostPolicy} onChange={(e) => setBoostPolicy((e.target as HTMLSelectElement).value as PolicyChoice)}>
                    {POLICY_OPTIONS.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
                  </select>
                </label>
                <label class="compose__policy-row">
                  <span>Can like</span>
                  <select class="select" value={favPolicy} onChange={(e) => setFavPolicy((e.target as HTMLSelectElement).value as PolicyChoice)}>
                    {POLICY_OPTIONS.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
                  </select>
                </label>
              </div>
            </div>
          )}

          {pickerOpen && (
            <div class="compose__emoji" role="region" aria-label="Emoji picker">
              <input
                class="input"
                value={pickerQuery}
                onInput={(e) => setPickerQuery((e.target as HTMLInputElement).value)}
                placeholder="Search emoji"
                aria-label="Search emoji"
              />
              {emojisStatus.value === 'loading' && (
                <div class="spinner-block" role="status">
                  <span class="spinner" />
                </div>
              )}
              {emojisStatus.value === 'error' && <p class="help-text">Could not load this server's custom emoji.</p>}
              {pickerItems.length === 0 && <p class="help-text">No emoji match that search.</p>}
              <div class="compose__emoji-scroll">
                {emojiGroups.map(([cat, list]) => (
                  <section key={cat || 'uncategorized'}>
                    {cat && <h3 class="compose__emoji-cat">{cat}</h3>}
                    <div class="compose__emoji-grid">
                      {list.map((e: PickerEmoji) => (
                        <button
                          key={e.key}
                          type="button"
                          class="compose__emoji-btn"
                          onClick={() => insertSnippet(e.char ?? `:${e.shortcode}:`)}
                          aria-label={e.shortcode ? `Insert :${e.shortcode}:` : `Insert ${e.name}`}
                          title={e.shortcode ? `:${e.shortcode}:` : e.name}
                        >
                          {e.url ? (
                            <img src={e.url} alt="" width={24} height={24} loading="lazy" />
                          ) : (
                            <span class="compose__emoji-char" role="img">{e.char}</span>
                          )}
                        </button>
                      ))}
                    </div>
                  </section>
                ))}
              </div>
            </div>
          )}
        </form>
      </div>
    </dialog>
  )
}
