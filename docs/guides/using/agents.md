---
title: Agents
description: Set up an AI provider once on the server, so spaces can have an agent.
---

# Agents

A space can have an agent. It isn't a chatbot, and it doesn't write notes for you. It acts on your
notes when you ask it to: it tags new notes, titles them, or keeps a digest up to date. You decide
what it does and when.

Agents are being built. Everything here works on the web and in the Android app. The iOS app doesn't
open notes for an agent yet, so there it acts only after you're done (new note done, note edit
done) and on deletes and moves.

## Give a space an agent

Once an admin has [set up AI](#set-it-up-admins), every space that has synced to the server can
have an agent. Open the space's **Space info** (**Info** in the space switcher, or the
space's row in Settings → Spaces): the agent has its own part there, **Agent**. On the web, its row
opens the agent's page; on Android, it's all in that part. Turning it on says where your notes will
be sent ([locked notes](locked-notes.md) never are) and opens the rest: what the space is for, its
rules and the notes it always reads, saved with **Save**. A space
with an agent shows the agent's glyph after its name, and moving a note into it asks first, since its
notes go to the AI provider.

Sharing and agents don't depend on each other. A shared space can have an agent, any member can set
it up, change it or turn it off, and sharing a space keeps its agent. Everyone in the space sees
what it did in Activity and can undo it. A run acts for whoever asked for it or wrote the note, so
its changes show as theirs.

While nothing's written yet, **Start from an example** (Tidy tags, Recipes) fills in an example's
purpose and rules, to change as you like. It never turns the agent on, and nothing is kept until
you save.

**What this space is for** describes the space and how the agent should behave. It goes with every
rule, and on its own does nothing:

- *This is my journal. Never change what I wrote. Keep notes findable with a small, stable set of tags.*
- *Recipes. I cook mostly vegetarian. Quantities in metric.*

Then add **rules**. A rule is a moment and what the agent does then; **Add rule** offers the
moments a space doesn't have a rule for yet. While a rule is empty, an **Idea** under it
offers a common one for that moment, to start from. A rule's moment, in its heading, can be changed
without losing what it says. Without rules, the agent does nothing.

| Rule | When |
| --- | --- |
| **When a new note is opened** | Before anything is typed |
| **When you start editing a note** | The first change to an existing note after it's opened, or after 3 minutes without changes |
| **When you ask, from the note's menu** | You pick the command in a note's ⋯ menu. Give it a name, such as *Fix typos* |
| **When you finish a new note** | A new note is closed, or left for 2 minutes |
| **When you finish editing a note** | The same, for a note that already existed |
| **When a note is deleted** | A note in the space is deleted |
| **When a note is moved here** | A note is moved here from another space |
| **When a note is moved away** | A note is moved from here to another space |

**Notes it always reads** are up to 5 notes, by title, that go with every rule, such as a *Digest*
the agent keeps. The agent acts faster when it doesn't have to look for them. Typing offers the
space's note titles; a title with no note yet is marked *not here yet*, and is read once there is
one, such as a note a rule has the agent make.

A new note opened, editing started and the menu command happen in the editor, and need a connection to
the server: offline, they're skipped, unless the connection comes back within about 10 seconds. The menu
command then says it didn't run, and why. The agent works on the note as you see it there, unsaved
typing included; close the note before it's done and it stops.

## While you write

When an agent works on the note you have open, it works on what you see: what you've typed but
not yet saved, and where your cursor is. While it works, a marker with the space's name shows where
it's likely to write: at your cursor, or at the top of a new note. You can keep typing. Its change
appears highlighted for a moment, with an **Undo** button beside it until you type again or a few
seconds pass. It's one step: **Undo**, or Ctrl+Z (⌘Z on a Mac) right away, takes it back and leaves
your typing alone. On Android, **Undo** is in a message at the bottom of the note instead.

Turning the agent off keeps everything you wrote, and so does removing AI from the server: the
agent is paused until it's back.

## Cards beside the note

An agent can also show you something without changing the note: a **card** between its paragraphs,
headed by the space's name. A card is never part of the note: it isn't saved, synced or searched,
and it goes when you close the note.

- A card to read, such as a question to start a new note, has **×** to dismiss it.
- A suggested change, such as *Fix typos* in the Tidy tags example, sits under the text it would
  change (a new title at the top, an addition where it would go) and shows what goes and what
  comes. **✓** makes the change, as one step you can undo like
  any other change the agent makes; **✗** leaves the note as it is. A suggestion whose text you've
  changed since goes by itself.

For the rules that run while you're in the note (a new note opened, editing started, the menu
command), the agent suggests changes to your own words and adds its own text directly, unless the
rule's prompt says otherwise. A rule's next run on the note replaces the cards its last one left. Cards show only in a note open on
the web or in the Android app: an agent can't leave one on a note nobody has open. With TalkBack, a
card is read after the note, and says where in the note it is. Activity counts what a run
showed, and how many of its suggestions you accepted.

## See what it did, and undo it

Everything an agent does is in **Activity**: pick **Activity…** beside **Agent** in Space info. On
the web and in the Android app.

Each run shows the note it was about, the trigger, when, and how it went: what it changed, that it
changed nothing, or why it failed or was skipped. Open one for its details: the model, the tokens it
used, its last words, and each note it changed, line by line. With a space chosen, today's token use
in that space is at the top, against the daily limit per space. A run that's waiting or going updates as it goes, and a new one shows as it
starts.

**Undo** puts each note the run changed back as it was, and deletes a note it made. A note you've
changed since is left alone, so undo never loses your writing; Activity says which and why. Undo
doesn't make the agent act again.

If an agent's last run on a note failed, the note says so, when you open it or as it happens, with a
link to the run in Activity. **Dismiss** hides it.

A change the agent made in your open note can be undone from Activity once the note has saved just
that change. If you typed on before it saved, use Undo in the note instead.

When you lock or delete a note, Activity forgets its text too, as the note itself does. As with
the note, backups made before still have it; see
[what locking doesn't cover](../../concepts/security-model.md#locked-notes). Runs are kept for 30
days.

## Time zone

"Today" depends on where you are, so your account has a time zone. It's filled in from the first
device that connects, and you can change it in **Settings → Server**.

## Set it up (admins)

notesd works with any API that speaks OpenAI's chat completions, such as OpenAI itself, OpenRouter,
or a model you run yourself with Ollama or LM Studio. The model must support tool calls, which is
how an agent reads and changes notes.

1. Open **Settings → Integrations**, and click **Connect** beside **AI**.
2. Enter:
   - **API address**: the address requests go to, ending before `/chat/completions`, such as
     `https://api.openai.com/v1` or `http://localhost:11434/v1` for Ollama. Plain `http://` is
     only accepted for a provider on your own network: a private address, a `.local` name, or a
     name with no dot, like a Docker Compose service.
   - **API key**: the provider's key. A local model that doesn't check keys still needs something
     here; any word will do.
   - **Model**: the model's name as the provider spells it.
   - **Daily tokens per space**: how many tokens each space's agent may use in a day.
     1,000,000 unless you change it.
3. Click **Test**. notesd asks the model to call a tool and says whether it did. A model that
   answers in text instead can't run agents.
4. Click **Connect**.

To change the provider or the daily tokens later, or to disconnect it, click **Manage** beside **AI**.

The key is stored on the server and never shown again; Settings shows its last four characters.
After saving, the server never returns or syncs it; only the admin's device has it, while entering
or testing it. Every account on the server uses this provider, but only admins can change
it. The first account is an admin; [`notesd add-user --admin`](../../reference/cli.md#add-user)
makes more.

Nothing is sent to the provider until someone turns on an agent for a space, except the test
itself, which sends no notes.
