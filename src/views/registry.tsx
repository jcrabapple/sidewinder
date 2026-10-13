import type { Route } from '../router'
import { TimelinePage } from './Timelines'
import { ThreadView } from './Thread'
import { NotificationsView } from './Notifications'
import { ProfileView } from './Profile'
import { SearchView } from './Search'
import { CollectionsView } from './Collections'
import { ListsView } from './Lists'
import { WrappedView } from './Wrapped'
import { CatchUpView } from './CatchUp'
import { FollowRequestsView } from './FollowRequests'
import { AccountRelationshipsView } from './AccountRelationships'
import { SettingsView } from './Settings'
import { navigate } from '../router'

export function AppRoutes({ route }: { route: Route }) {
  const [seg0, seg1, seg2] = route.segments

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
    case 'catchup':
      return <CatchUpView />
    case 'statuses':
      return seg1 ? <ThreadView id={seg1} /> : <NotFound path={route.href} />
    case 'accounts':
      if (!seg1) return <NotFound path={route.href} />
      if (seg2 === 'followers' || seg2 === 'following') {
        return <AccountRelationshipsView id={seg1} kind={seg2} />
      }
      return <ProfileView id={seg1} />
    case 'search':
      return <SearchView />
    case 'favourites':
      return <CollectionsView kind="favourites" />
    case 'bookmarks':
      return <CollectionsView kind="bookmarks" />
    case 'follow_requests':
      return <FollowRequestsView />
    case 'wrapped':
      return <WrappedView />
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
