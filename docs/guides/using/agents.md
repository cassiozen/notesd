---
title: Agents
description: Set up an AI provider once on the server, so spaces can have an agent.
---

# Agents

A space can have an agent. It isn't a chatbot, and it doesn't write notes for you. It acts on your
notes when you ask it to: it tags new notes, titles them, or keeps a digest up to date. You decide
what it does and when.

Agents are being built. They act on **new note done**, **note edit done**, **note deleted** and the
two **moved** triggers. Note created, started editing and the menu command come with the editor
work; the activity log and undo come next.

## Give a space an agent

Once an admin has [set up AI](#set-it-up-admins), every space that has synced to the server has an
**Agent** switch: in **Edit
space** (Settings → Spaces), and as **Add an agent** when you make a new space, which also offers
examples to start from (Journal, Meeting notes, Recipes, Auto-tags). Turning it on says where your
notes will be sent. [Locked notes](locked-notes.md) never are.

**What this space is for** describes the space and how the agent should behave. It goes with every
prompt, and on its own does nothing:

- *This is my journal. Never change what I wrote. Keep notes findable with a small, stable set of tags.*
- *Recipes. I cook mostly vegetarian. Quantities in metric.*

Then write a prompt for each moment you want the agent to act. A moment without a prompt does
nothing.

| Trigger | When |
| --- | --- |
| **Note created** | A new note is opened, before anything is typed. *Not yet: comes with the editor work* |
| **Started editing** | The first change to a note after it's opened, or after 3 minutes without changes. *Not yet* |
| **New note done** | A new note is closed, or left for 2 minutes |
| **Note edit done** | The same, for a note that already existed |
| **Note deleted** | A note in the space is deleted |
| **Note moved in** | A note is moved here from another space |
| **Note moved out** | A note is moved from here to another space |
| **Run from menu** | You pick the command in a note's menu. Give it a name, such as *Fix typos*. *Not yet* |

Turning the agent off keeps everything you wrote, and so does removing AI from the server: the
agent is paused until it's back.

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
   - **Daily tokens per account**: how many tokens each account's agents may use in a day.
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
