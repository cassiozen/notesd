---
title: Agents
description: Set up an AI provider once on the server, so spaces can have an agent.
---

# Agents

A space can have an agent. It isn't a chatbot, and it doesn't write notes for you. It acts on your
notes when you ask it to: it tags new notes, titles them, or keeps a digest up to date. You decide
what it does and when.

Agents are being built. For now, an admin can set up the AI provider they'll use.

## Set it up (admins)

notesd works with any API that speaks OpenAI's chat completions, such as OpenAI itself, OpenRouter,
or a model you run yourself with Ollama or LM Studio. The model must support tool calls, which is
how an agent reads and changes notes.

1. Open **Settings → Integrations**.
2. Under **AI**, enter:
   - **API address**: the address requests go to, ending before `/chat/completions`, such as
     `https://api.openai.com/v1` or `http://localhost:11434/v1` for Ollama. Plain `http://` is
     only accepted for a provider on your own network: a private address, a `.local` name, or a
     name with no dot, like a Docker Compose service.
   - **API key**: the provider's key. A local model that doesn't check keys still needs something
     here; any text will do.
   - **Model**: the model's name as the provider spells it.
   - **Daily tokens per account**: how many tokens each account's agents may use in a day.
     1,000,000 unless you change it.
3. Click **Test**. notesd asks the model to call a tool and says whether it did. A model that
   answers in text instead can't run agents.
4. Click **Save**.

The key is stored on the server and never shown again; Settings shows its last four characters.
After saving, the server never returns or syncs it; only the admin's device has it, while entering
or testing it. Every account on the server uses this provider, but only admins can change
it. The first account is an admin; [`notesd add-user --admin`](../../reference/cli.md#add-user)
makes more.

Nothing is sent to the provider until someone turns on an agent for a space, except the test
itself, which sends no notes.
