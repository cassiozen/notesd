// Picks the server and opens it. Commands are the app's own (src-tauri/src/main.rs); only this page may call them.
const invoke = (cmd, args) => window.__TAURI_INTERNALS__.invoke(cmd, args)
const $ = (id) => document.getElementById(id)

// A load that fails leaves this page in place, with no event to say so: if it's still here by then, the server
// didn't answer and its service worker had nothing kept. A slow server that answers later still replaces it.
const UNREACHABLE_AFTER_MS = 6000

let server = null
let giveUp = 0

function open(url) {
  server = url
  $('setup').hidden = true
  $('opening').hidden = false
  $('unreachable').hidden = true
  const host = new URL(url).host
  $('opening-title').textContent = `Opening ${host}…`
  clearTimeout(giveUp)
  giveUp = setTimeout(() => {
    $('opening-title').textContent = `Can’t reach ${host}`
    $('unreachable').hidden = false
    $('retry').focus()
  }, UNREACHABLE_AFTER_MS)
  // Not location.replace: a load the page starts itself goes to the Safari web app, when there's one for this server.
  invoke('open_server')
}

function ask(changing) {
  clearTimeout(giveUp)
  $('opening').hidden = true
  $('setup').hidden = false
  $('cancel').hidden = !(changing && server)
  $('address').value = server ?? ''
  showError(null)
  $('address').focus()
  $('address').select()
}

function showError(message) {
  $('error').textContent = message ?? ''
  $('error').hidden = !message
  $('address').toggleAttribute('aria-invalid', !!message)
}

function busy(on) {
  $('connect').disabled = on
  $('connect').textContent = on ? 'Checking…' : 'Connect'
  $('address').readOnly = on
}

$('setup').addEventListener('submit', async (event) => {
  event.preventDefault()
  busy(true)
  showError(null)
  try {
    open(await invoke('set_server', { address: $('address').value }))
  } catch (err) {
    showError(String(err))
    $('address').focus()
  } finally {
    busy(false)
  }
})
$('cancel').addEventListener('click', () => open(server))
$('retry').addEventListener('click', () => open(server))
$('change').addEventListener('click', () => ask(true))

server = await invoke('get_server')
if (server && !new URLSearchParams(location.search).has('change')) open(server)
else ask(true)
