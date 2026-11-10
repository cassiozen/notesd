// Light or dark as picked on this browser, else the system's (§7.10). In <head> so a page never
// flashes the other theme; without it the toggle stays hidden.
;(function () {
  var root = document.documentElement
  var key = 'notesd-theme'
  try {
    var picked = localStorage.getItem(key)
    if (picked === 'light' || picked === 'dark') root.dataset.theme = picked
  } catch (e) {}

  function dark() {
    var t = root.dataset.theme || (matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light')
    return t === 'dark'
  }

  document.addEventListener('DOMContentLoaded', function () {
    var button = document.querySelector('.theme-toggle')
    if (!button) return
    function label() {
      button.setAttribute('aria-label', dark() ? 'Use the light theme' : 'Use the dark theme')
    }
    label()
    button.hidden = false
    button.addEventListener('click', function () {
      root.dataset.theme = dark() ? 'light' : 'dark'
      try {
        localStorage.setItem(key, root.dataset.theme)
      } catch (e) {}
      label()
    })
  })

  // The tags beside the page scroll on their own, and each page opens them at the top: bring the
  // current one into view.
  document.addEventListener('DOMContentLoaded', function () {
    var rail = document.querySelector('.tag-rail')
    var current = rail && rail.querySelector('[aria-current]')
    if (!current || rail.scrollHeight <= rail.clientHeight) return
    // Clear of the fade along its bottom.
    if (current.offsetTop + current.offsetHeight > rail.clientHeight - 32) {
      rail.scrollTop = current.offsetTop - rail.clientHeight / 2
    }
  })
})()
