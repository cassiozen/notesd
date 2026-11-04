for (const button of document.querySelectorAll("[data-copy]")) {
  const source = document.getElementById(button.dataset.copy);

  let reset;

  button.addEventListener("click", async () => {
    try {
      await navigator.clipboard.writeText(source.textContent);
      button.textContent = "Copied";
      button.classList.add("is-copied");
      // A second click restarts the timer instead of being cut short by the first one
      clearTimeout(reset);
      reset = setTimeout(() => {
        button.textContent = "Copy";
        button.classList.remove("is-copied");
      }, 1500);
    } catch {
      // Clipboard API blocked (e.g. insecure context): select the text so the user can copy manually
      getSelection().selectAllChildren(source);
    }
  });
}

for (const button of document.querySelectorAll("[data-open]")) {
  const dialog = document.getElementById(button.dataset.open);
  const video = dialog.querySelector("video");

  button.addEventListener("click", () => {
    dialog.showModal();
    video.play();
  });

  dialog.querySelector("[data-close]").addEventListener("click", () => dialog.close());

  // Click on the backdrop lands on the dialog element itself, not its children
  dialog.addEventListener("click", (event) => {
    if (event.target === dialog) dialog.close();
  });

  dialog.addEventListener("close", () => {
    video.pause();
    video.currentTime = 0;
  });
}

// Elements that scroll into view together stagger in DOM order, so a grid row cascades
// left to right whatever the column count. CSS decides whether anything actually animates.
const revealObserver = new IntersectionObserver(
  (entries) => {
    entries
      .filter((entry) => entry.isIntersecting)
      .map((entry) => entry.target)
      .sort((a, b) => (a.compareDocumentPosition(b) & Node.DOCUMENT_POSITION_FOLLOWING ? -1 : 1))
      .forEach((target, index) => {
        target.style.setProperty("--reveal-delay", `${index * 70}ms`);
        target.classList.add("is-revealed");
        revealObserver.unobserve(target);
      });
  },
  { rootMargin: "0px 0px -8% 0px" },
);

for (const target of document.querySelectorAll("[data-reveal]")) {
  revealObserver.observe(target);
}
