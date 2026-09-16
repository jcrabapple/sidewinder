import { render } from 'preact'
import { App } from './App'
import { registerPwa } from './pwa'
import './app.css'

registerPwa()
render(<App />, document.getElementById('app')!)
