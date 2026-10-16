# Quote posts (link-quote) design

## Server facts (verified 2026-10-16)

- gts.example.org runs GoToSocial `0.22.2+git-fb2bf13`. The upstream GTS API spec
  (docs.gotosocial.org latest swagger) contains NO quote support: no `quoted_status_id`
  form field, no `quote` field on Status, no quote approval endpoints. Native
  (Mastodon 4.5 / FEP-044f) quote posts cannot be created through this server.
- So Sidewinder implements the fediverse fallback convention: a quote post is a normal
  status whose text ends with a line `RE: <url of the quoted post>`. Other clients show
  commentary + a link; Sidewinder renders the quoted post as an embedded card.

## UX

1. **Long-press Boost** (only when the post is boostable: public/unlisted/local and not
   already boosted, i.e. where long-press is wired today) opens a small menu (Material 3
   `DropdownMenu` anchored to the boost button, or a `ModalBottomSheet`) with two items:
   - `Boost` (icon Repeat): existing behavior, honoring the "confirm before boost" setting.
   - `Quote` (icon FormatQuote): opens the composer in quote mode.
   This replaces the current `boostContext` AlertDialog ("Boost this post?").
   If the post is already boosted, long-press stays inert (tap unboosts) but the
   menu must still be reachable for Quote: show the menu with `Undo boost` + `Quote`.
   Private/direct posts: no Quote (same rule as canBoost).
2. **Composer quote mode** (route `compose?quote={id}`): fetch the quoted status first and
   gate composition on it (loading -> composed; failure -> the existing LoadFailedScreen
   with Back/Retry, copy "Could not load the post you are quoting."). Title "Quote post".
   Above the toolbar/under the text field show the quoted post as a read-only card
   (reuse the reply-context card styling: avatar, name, @acct, 3-line plain-text excerpt
   via `StatusText.plain`). Commentary is optional? NO: Post is disabled until the
   commentary is non-blank (a bare link is a boost with extra steps).
   Visibility defaults to the user's default but cannot be more public than the quoted post
   (reuse the reply visibility demotion helper). Media, CW, poll remain available.
   Drafts: quote mode does not touch the New-post draft (same as Reply).
3. **Posting**: status text = `commentary.trimEnd() + "\n\nRE: " + quotedUrl` where
   `quotedUrl = quoted.url ?: quoted.uri`. The character counter counts the appended line
   (instance `characters_reserved_per_url` = 25 for the URL, plus 4 for `RE: ` and 2 for
   the blank line); the field shows only the commentary. Content type stays markdown.
   After success: `TimelineStore.applyPosted`, dismiss.
4. **Rendering** (StatusCard, every surface that uses it): detect a quote in a status:
   - Primary: plain text ends with `RE: <url>` (case-insensitive `RE:`, optional
     surrounding whitespace) and that url is the href of a link in the content.
   - Secondary: the LAST link in the content is a status permalink matching one of
     `https?://host/@user/statuses/ID` (GTS), `https?://host/@user/ID` (Mastodon),
     `https?://host/users/user/statuses/ID`, `https?://host/notice/ID` (Pleroma/Akkoma),
     `https?://host/notes/ID` (Misskey) and it is NOT a mention or hashtag link.
   When detected: hide the trailing `RE: <url>` paragraph from the rendered body (only for
   the primary form; a bare trailing link stays visible), and render a `QuoteCard` below
   the body: bordered rounded box, avatar 20dp + display name + @acct, up to 4 lines of
   plain text (`StatusText.plain`), a CW line instead of text when the quoted post has a
   spoiler, a single 16:9 thumbnail of the first image when present (respect the
   showMediaPreviews setting), tap opens the quoted thread. Accessible: one merged node
   "Quoted post by NAME: excerpt", onClickLabel "Open quoted post".
   States: loading (a 56dp placeholder box with a small progress indicator), resolved
   (card), unresolvable (a compact "Quoted post unavailable. Open link" row that opens
   the URL via AppActions.openUrl). Never block the parent card on resolution.
   Never nest: a QuoteCard does not render its own quote.
5. **Resolution** (`core/quote/QuoteResolver.kt`, no Compose):
   - `QuoteLinks.detect(html: String, mentions: List<Mention>, tags: List<Tag>): QuoteRef?`
     pure, returns `QuoteRef(url: String, explicitRe: Boolean)`.
   - `QuoteLinks.stripReLine(html: String, url: String): String` pure, removes the trailing
     paragraph / line that is exactly `RE: <a href=url>...</a>` (and a trailing `<br>` RE
     line) from the HTML; returns html unchanged when not present.
   - `QuoteLinks.localStatusId(url: String, base: String): String?` pure: when the url's
     host equals the session base host and path is `/@user/statuses/ID` (or `/users/user/statuses/ID`),
     return ID.
   - `object QuoteResolver { suspend fun resolve(api: Api, base: String, url: String): Status? }`:
     local id -> `GET /api/v1/statuses/{id}`; else `GET /api/v2/search` with
     `q=url, type=statuses, resolve=true, limit=1`. Process-wide LRU cache (200) of
     url -> Status, plus a negative cache (url -> failedAt, retry after 5 minutes) so a
     dead link is not re-searched on every scroll. Concurrent requests for the same url
     share one in-flight Deferred. Timeout 20s (GTS remote resolve can hang). Rethrow
     CancellationException; other failures -> null + negative cache entry.
   - JVM unit tests for detect/stripReLine/localStatusId (GTS, Mastodon, Akkoma URL shapes;
     mention/hashtag links ignored; RE line with/without `<p>`; uppercase/lowercase RE;
     no false positive on a post that merely links an article) and for the cache policy
     (extract the pure decision `shouldRetry(failedAtMs, nowMs)`).

## Wiring

- `ui/app/AppLocals.kt`: add `val openQuote: (statusId: String) -> Unit = {}` to AppActions.
- `MainActivity.kt`: route `compose?replyTo={replyTo}&share={share}&edit={edit}&quote={quote}`
  (add `quote` navArgument nullable, default null, alongside the others); `openQuote`
  navigates to `compose?quote=$id`; fetch + gate exactly like replyTo/edit.
- `ComposeScreen`: new param `quoting: Status? = null` -> `ComposeMode.Quote`.
- Fingerprint/dirty and discard prompt: quote mode behaves like Reply.
