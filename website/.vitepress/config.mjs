import path from "node:path";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import { defineConfig } from "vitepress";

const repoDir = fileURLToPath(new URL("../..", import.meta.url));
const docsDir = path.join(repoDir, "docs");
const repo = "https://github.com/cassiozen/notesd";
const vueDir = path.dirname(createRequire(import.meta.url).resolve("vue/package.json"));

// README.md is the index on GitHub; numeric prefixes only order files there.
const rewrite = (file) => file.replace(/^README\.md$/, "index.md").replace(/(^|\/)\d+-/, "$1");

// Docs keep plain relative links to source files so they also work when browsed on GitHub.
// VitePress applies `rewrites` to page URLs but not to links, so links into the site are rewritten
// here too. Contributor docs and repo files outside docs/ aren't on the site; links go to GitHub.
function rewriteLinks(md) {
  const render = md.renderer.rules.link_open;
  md.renderer.rules.link_open = (tokens, idx, options, env, self) => {
    const token = tokens[idx];
    const href = token.attrGet("href");
    if (href && !/^[a-z]+:/i.test(href) && !href.startsWith("#")) {
      const [file, hash] = href.split("#");
      const target = path.relative(repoDir, path.resolve(path.dirname(env.realPath ?? env.path), file));
      const anchor = hash ? `#${hash}` : "";
      if (!target.startsWith("docs/") || target.startsWith("docs/contributing/")) {
        token.attrSet("href", `${repo}/blob/main/${target}${anchor}`);
      } else if (target.endsWith(".md")) {
        token.attrSet("href", `/${rewrite(path.relative("docs", target))}${anchor}`);
      }
    }
    return render(tokens, idx, options, env, self);
  };
}

export default defineConfig({
  srcDir: "../docs",
  srcExclude: ["contributing/**"],
  outDir: "dist/docs",
  base: "/docs/",
  rewrites: rewrite,

  title: "notesd",
  description: "Guides and reference for running and using notesd.",
  appearance: "force-dark",
  lastUpdated: true,
  ignoreDeadLinks: "localhostLinks",
  head: [
    ["link", { rel: "icon", href: "/docs/icon.png" }],
    ["link", { rel: "preconnect", href: "https://fonts.googleapis.com" }],
    ["link", { rel: "preconnect", href: "https://fonts.gstatic.com", crossorigin: "" }],
    [
      "link",
      {
        rel: "stylesheet",
        href: "https://fonts.googleapis.com/css2?family=Instrument+Serif:ital@0;1&family=Instrument+Sans:wght@400;500;600&family=JetBrains+Mono:wght@400;500&display=swap",
      },
    ],
  ],

  vite: {
    // Serves docs/images/icon.png at the root, for the logo and favicon.
    publicDir: path.join(docsDir, "images"),
    // Pages compile to modules inside docs/, where pnpm's strict node_modules has no vue.
    resolve: { alias: [{ find: /^vue(?=$|\/)/, replacement: vueDir }] },
  },

  markdown: {
    config: rewriteLinks,
  },

  themeConfig: {
    logo: "/icon.png",
    // Used as-is (no base), so the logo leads back to the landing page.
    logoLink: { link: "/", target: "_self" },
    nav: [
      { text: "Get started", link: "/get-started/run-the-server" },
      { text: "FAQ", link: "/faq" },
    ],
    socialLinks: [{ icon: "github", link: repo }],
    search: { provider: "local" },
    editLink: { pattern: `${repo}/edit/main/docs/:path`, text: "Edit this page on GitHub" },
    outline: [2, 3],
    sidebar: [
      {
        text: "Get started",
        items: [
          { text: "1. Run the server", link: "/get-started/run-the-server" },
          { text: "2. Create your account", link: "/get-started/create-your-account" },
          { text: "3. Set up HTTPS", link: "/get-started/set-up-https" },
          { text: "4. Write your first note", link: "/get-started/write-your-first-note" },
          { text: "5. Add your phone", link: "/get-started/add-your-phone" },
        ],
      },
      {
        text: "Using notesd",
        items: [
          { text: "Writing and Markdown", link: "/guides/using/writing-and-markdown" },
          { text: "Tags, spaces and search", link: "/guides/using/organize-and-find" },
          { text: "Locked notes", link: "/guides/using/locked-notes" },
          { text: "Emailing notes", link: "/guides/using/email" },
          { text: "Offline and sync", link: "/guides/using/offline-and-sync" },
          { text: "Settings", link: "/guides/using/settings" },
        ],
      },
      {
        text: "Apps",
        items: [
          { text: "Web app", link: "/guides/apps/web" },
          { text: "Android", link: "/guides/apps/android" },
          { text: "iPhone", link: "/guides/apps/iphone" },
        ],
      },
      {
        text: "Self-hosting",
        items: [
          { text: "Run with Docker", link: "/guides/self-hosting/docker" },
          { text: "Run the binary with systemd", link: "/guides/self-hosting/binary-systemd" },
          { text: "Network access and HTTPS", link: "/guides/self-hosting/https" },
          { text: "Accounts", link: "/guides/self-hosting/users" },
          { text: "Backups", link: "/guides/self-hosting/backups" },
          { text: "Upgrading", link: "/guides/self-hosting/upgrading" },
          { text: "Markdown mirror and export", link: "/guides/self-hosting/markdown-mirror-and-export" },
          { text: "Monitoring and logs", link: "/guides/self-hosting/monitoring-and-logs" },
        ],
      },
      {
        text: "Concepts",
        items: [
          { text: "How sync works", link: "/concepts/how-sync-works" },
          { text: "Security model", link: "/concepts/security-model" },
          { text: "Where your data lives", link: "/concepts/where-data-lives" },
        ],
      },
      {
        text: "Reference",
        items: [
          { text: "Command line", link: "/reference/cli" },
          { text: "HTTP API", link: "/reference/api" },
          { text: "FAQ", link: "/faq" },
        ],
      },
    ],
  },
});
