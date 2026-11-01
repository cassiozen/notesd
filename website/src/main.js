for (const button of document.querySelectorAll("[data-copy]")) {
  const source = document.getElementById(button.dataset.copy);

  button.addEventListener("click", async () => {
    try {
      await navigator.clipboard.writeText(source.textContent);
      button.textContent = "Copied";
      setTimeout(() => (button.textContent = "Copy"), 1500);
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
