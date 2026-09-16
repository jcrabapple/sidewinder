/**
 * Global compose dialog: new posts, replies, edits. Supports media uploads
 * with alt text, polls, content warnings, visibility, and a custom-emoji
 * picker. Opened via openCompose() from state/compose.ts.
 */
import { useEffect, useRef, useState } from 'preact/hooks'
import { composeOpen, composeContext, closeCompose } from '../state/compose'
import { api } from '../state/auth'
import { settings } from '../state/settings'
import { toast } from '../state/toast'
import { customEmojis, emojisStatus, ensureEmojis, loadEmojiSet, pickerEmojis, filterPickerEmojis, groupPickerEmojis, type PickerEmoji } from '../state/emojis'
import type { Instance, MediaAttachment, Status } from '../api/types'
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

interface DraftMedia {
  attachment: MediaAttachment
  uploading: boolean
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
  const dlgRef = useRef<HTMLDialogElement>(null)
  const textRef = useRef<HTMLTextAreaElement>(null)
  const fileRef = useRef<HTMLInputElement>(null)
  const lastFocus = useRef<Element | null>(null)

  const [text, setText] = useState('')
  const [cw, setCw] = useState(false)
  const [spoiler, setSpoiler] = useState('')
  const [visibility, setVisibility] = useState<Visibility>(settings.value.defaultVisibility)
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

  const editing = ctx?.editing
  const replyTo = ctx?.replyTo

  // Sync native <dialog> with the composeOpen signal.
  useEffect(() => {
    const d = dlgRef.current
    if (!d) return
    if (open && !d.open) {
      lastFocus.current = document.activeElement
      d.showModal()
    } else if (!open && d.open) {
      d.close()
    }
  }, [open])

  // Reset the draft whenever the dialog opens with a new context.
  useEffect(() => {
    if (!open) return
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
      !editing && replyTo && replyTo.visibility !== 'local'
        ? replyTo.visibility
        : settings.value.defaultVisibility,
    )
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

  async function addFiles(files: FileList | null) {
    if (!files || busy) return
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
      setMedia((m) => [...m, placeholder])
      try {
        let att = await api.value.request<MediaAttachment>('/api/v2/media', { method: 'POST', body: fd })
        // 202 processing: poll until the URL materializes.
        for (let i = 0; !att.url && i < 6; i++) {
          await new Promise((r) => setTimeout(r, 1500))
          att = await api.value.get<MediaAttachment>(`/api/v1/media/${att.id}`)
        }
        if (!att.url) throw new Error('The server is still processing that file. Wait a moment and try again.')
        setMedia((m) => m.map((d) => (d.attachment.id === placeholder.attachment.id ? { attachment: att, uploading: false } : d)))
      } catch (e) {
        setMedia((m) => m.filter((d) => d.attachment.id !== placeholder.attachment.id))
        setError(e instanceof Error ? e.message : 'Upload failed. Try a different file.')
      }
    }
  }

  function removeMedia(id: string) {
    setMedia((m) => m.filter((d) => d.attachment.id !== id))
  }

  async function saveAlt(id: string, description: string) {
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
  const uploading = media.some((d) => d.uploading)
  const missingAlt = media.some((d) => !d.uploading && !d.attachment.description?.trim())
  const pollValid = !pollOn || (usedPollOptions.length >= 2 && usedPollOptions.every((o) => o.length <= limits.pollMaxChars))
  const hasContent = text.trim().length > 0 || media.length > 0
  const canSubmit = hasContent && !overLimit && !uploading && pollValid && !busy

  async function submit() {
    if (!canSubmit) return
    if (missingAlt && settings.value.requireAltText && !altWarned) {
      setAltWarned(true)
      setError('Some attachments have no description. Press post again to publish anyway.')
      return
    }
    setBusy(true)
    setError(null)
    const body: Record<string, unknown> = {
      status: text,
      sensitive: cw,
      spoiler_text: cw ? spoiler : '',
      visibility,
      content_type: 'text/markdown',
    }
    if (replyTo) body.in_reply_to_id = replyTo.id
    if (media.length) body.media_ids = media.map((d) => d.attachment.id)
    if (!editing && (replyPolicy !== 'default' || boostPolicy !== 'default' || favPolicy !== 'default')) {
      const ip: Record<string, unknown> = {}
      const r = rulesFor(replyPolicy); if (r) ip.can_reply = r
      const b = rulesFor(boostPolicy); if (b) ip.can_reblog = b
      const f = rulesFor(favPolicy); if (f) ip.can_favourite = f
      if (Object.keys(ip).length > 0) body.interaction_policy = ip
    }
    if (pollOn && !editing) {
      body.poll = {
        options: usedPollOptions,
        expires_in: Math.min(Math.max(pollExpiry, limits.pollMinExp), limits.pollMaxExp),
        multiple: pollMultiple,
      }
    }
    try {
      if (editing) {
        await api.value.put<Status>(`/api/v1/statuses/${editing.id}`, body)
        toast('Post updated')
      } else {
        await api.value.request<Status>('/api/v1/statuses', {
          method: 'POST',
          body,
          headers: { 'Idempotency-Key': crypto.randomUUID() },
        })
        toast('Posted')
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
      class="sw-dialog compose"
      aria-labelledby="compose-title"
      onClose={() => {
        if (composeOpen.value) closeCompose()
      }}
      onCancel={() => {
        if (composeOpen.value) closeCompose()
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
            onInput={(e) => setText((e.target as HTMLTextAreaElement).value)}
            onKeyDown={(e) => {
              if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') {
                e.preventDefault()
                void submit()
              }
            }}
            placeholder={replyTo ? 'Write your reply' : "What's on your mind?"}
            aria-label="Post text"
            rows={6}
            aria-busy={busy || undefined}
          />
          <div class="compose__meta">
            <span class={`compose__count num${overLimit ? ' compose__count--over' : ''}`} aria-live="polite">
              {text.length + (cw ? spoiler.length : 0)} / {limits.maxChars}
            </span>
          </div>

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
                          onChange={(e) => void saveAlt(d.attachment.id, (e.target as HTMLInputElement).value)}
                        />
                      </label>
                      <button
                        type="button"
                        class="icon-btn compose__media-remove"
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
            <fieldset class="compose__poll">
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
              disabled={pollOn || media.length >= limits.maxMedia || uploading}
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
              disabled={Boolean(editing) || media.length > 0}
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
              </select>
            </label>
            <button type="submit" class="btn btn--primary compose__submit" disabled={!canSubmit}>
              {busy ? 'Publishing…' : editing ? 'Save changes' : replyTo ? 'Reply' : 'Post'}
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
