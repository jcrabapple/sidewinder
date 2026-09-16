import type { Route } from '../router'
import { TimelinePage } from './Timelines'
import { ThreadView } from './Thread'
import { NotificationsView } from './Notifications'
import { ProfileView } from './Profile'
import { SearchView } from './Search'
import { CollectionsView } from './Collections'
import { ListsView } from './Lists'
import { FollowRequestsView } from './FollowRequests'
import { SettingsView } from './Settings'
import { navigate } from '../router'

export function AppRoutes({ route }: { route: Route }) {
  const [seg0, seg1] = route.segments

  switch (seg0) {
    case undefined:
      return <TimelinePage kind="home" />
    case 'home':
      return <TimelinePage kind="home" />
    case 'local':
      return <TimelinePage kind="local" />
    case 'federated':
      return <TimelinePage kind="federated" />
    case 'tags':
      return seg1 ? <TimelinePage kind="tag" tag={seg1} /> : <NotFound path={route.href} />
    case 'lists':
      return seg1 ? <TimelinePage kind="list" listId={seg1} /> : <ListsView />
    case 'notifications':
      return <NotificationsView />
    case 'statuses':
      return seg1 ? <ThreadView id={seg1} /> : <NotFound path={route.href} />
    case 'accounts':
      return seg1 ? <ProfileView id={seg1} /> : <NotFound path={route.href} />
    case 'search':
      return <SearchView />
    case 'favourites':
      return <CollectionsView kind="favourites" />
    case 'bookmarks':
      return <CollectionsView kind="bookmarks" />
    case 'follow_requests':
      return <FollowRequestsView />
    case 'settings':
      return <SettingsView />
    default:
      return <NotFound path={route.href} />
  }
}

function NotFound({ path }: { path: string }) {
  return (
    <div class="state-block" style="min-height:50dvh">
      <h2>That page doesn't exist</h2>
      <p>
        Nothing lives at <code>#{path}</code>.{' '}
        <a href="#/" onClick={(e) => { e.preventDefault(); navigate('/') }}>
          Back to your timeline
        </a>
      </p>
    </div>
  )
}
