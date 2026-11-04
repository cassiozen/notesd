// Picks the server and opens it. Commands are the app's own (src-tauri/src/main.rs); only this page may call them.
const invoke = (cmd, args) => window.__TAURI_INTERNALS__.invoke(cmd, args)
const $ = (id) => document.getElementById(id)

// A load that fails leaves this page in place, with no event to say so: if it's still here by then, the server
// didn't answer and its service worker had nothing kept. A slow server that answers later still replaces it.
const UNREACHABLE_AFTER_MS = 6000

const still = matchMedia('(prefers-reduced-motion: reduce)')

let server = null
let giveUp = 0

/** `opening`, `unreachable` or `setup`: what the page shows, for its CSS. */
function state(name) {
  document.body.dataset.state = name
}

/** Fades the setup form out on its way to the next step, rather than dropping it. */
async function leave(step) {
  if (step.hidden) return
  if (!still.matches) {
    await step.animate([{ opacity: 1 }, { opacity: 0, transform: 'translateY(-8px) scale(0.985)' }], {
      duration: 200,
      easing: 'cubic-bezier(0.2, 0.8, 0.2, 1)',
    }).finished
  }
  step.hidden = true
}

async function open(url) {
  server = url
  clearTimeout(giveUp)
  const host = new URL(url).host
  // Not location.replace: a load the page starts itself goes to the Safari web app, when there's one for this server.
  invoke('open_server')
  await leave($('setup-step'))
  state('opening')
  $('opening').hidden = false
  $('unreachable').hidden = true
  $('opening-title').textContent = `Opening ${host}…`
  giveUp = setTimeout(() => {
    state('unreachable')
    $('opening-title').textContent = `Can’t reach ${host}`
    $('unreachable').hidden = false
    $('retry').focus()
  }, UNREACHABLE_AFTER_MS)
}

function ask(changing) {
  clearTimeout(giveUp)
  busy(false)
  state('setup')
  $('opening').hidden = true
  $('setup-step').hidden = false
  $('cancel').hidden = !(changing && server)
  $('address').value = server ?? ''
  showError(null)
  $('address').focus()
  $('address').select()
}

function showError(message) {
  $('error').textContent = message ?? ''
  $('error').hidden = !message
  if (message) $('address').setAttribute('aria-invalid', 'true')
  else $('address').removeAttribute('aria-invalid')
  if (message && !still.matches) {
    $('address').animate([{}, { transform: 'translateX(-6px)' }, { transform: 'translateX(6px)' }, {}], {
      duration: 320,
      easing: 'ease-in-out',
    })
  }
}

function busy(on) {
  $('connect').disabled = on
  // A check that succeeds after Cancel would still switch servers.
  $('cancel').disabled = on
  $('connect').textContent = on ? 'Checking…' : 'Connect'
  $('connect').setAttribute('aria-busy', String(on))
  $('address').readOnly = on
}

$('setup').addEventListener('submit', async (event) => {
  event.preventDefault()
  busy(true)
  showError(null)
  let url
  try {
    url = await invoke('set_server', { address: $('address').value })
  } catch (err) {
    busy(false)
    showError(String(err))
    $('address').focus()
    return
  }
  // Still busy while the form fades out; asked again later, it starts afresh.
  void open(url)
})
$('cancel').addEventListener('click', () => void open(server))
$('retry').addEventListener('click', () => void open(server))
$('change').addEventListener('click', () => ask(true))

server = await invoke('get_server')
if (server && !new URLSearchParams(location.search).has('change')) void open(server)
else ask(true)
