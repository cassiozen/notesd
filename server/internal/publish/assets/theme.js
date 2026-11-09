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
})()
