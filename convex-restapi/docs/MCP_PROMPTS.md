# MCP Prompts — Design and Authoring Guide

Design of the Convex MCP prompts system and principles for writing effective prompts.

## Overview

MCP prompts are server-defined message templates that guide LLMs through tasks using available tools. They are **user-controlled** — triggered by slash commands or explicit selection, not auto-invoked.

The Convex MCP server exposes prompts via `prompts/list` and `prompts/get` per the [MCP specification](https://modelcontextprotocol.io/specification/2025-11-25/server/prompts). Prompts serve two purposes:

1. **Guide the LLM** through a multi-step workflow with the right tools
2. **Teach the LLM about Convex** — domain knowledge it needs to do the job correctly

## Architecture

### File structure

All prompt content lives in JSON resource files — not in Java source code:

```
src/main/resources/convex/restapi/mcp/prompts/
  explore-account.json
  network-status.json
  convex-guide.json
  create-account.json      (signing service required)
  deploy-contract.json     (signing service required)
  transfer-funds.json      (signing service required)
```

### JSON format

Each file contains metadata, arguments, and message templates:

```json
{
  "name": "prompt-name",
  "title": "Human-Readable Title",
  "description": "One-line description for discovery.",
  "arguments": [
    {
      "name": "argName",
      "description": "What this argument is for",
      "required": true
    }
  ],
  "messages": [
    { "role": "user", "content": "Persona and Convex context..." },
    { "role": "user", "content": "The actual request with ${argName} substitution..." },
    { "role": "assistant", "content": "Prefill that guides the response direction..." }
  ]
}
```

### Three-message pattern

Each prompt returns three messages:

1. **Persona** (user) — sets the LLM's role and teaches it what it needs to know about Convex for this task. This is the most important message.
2. **Request** (user) — the actual task, kept concise. Uses `${argName}` placeholders substituted from user-supplied arguments.
3. **Prefill** (assistant) — a short assistant message that anchors the response direction.

### Template substitution

`${variableName}` placeholders in message content are replaced with argument values at render time. Unmatched placeholders are left as-is. The `McpPrompt` class handles this automatically.

### Registration

`McpPrompts.java` registers prompts at startup. Three are always available; three more are conditionally registered when the signing service is configured. Registration is just loading JSON files — no per-prompt Java classes needed.

For prompts that require custom argument processing (beyond simple substitution), subclass `McpPrompt` and override `render()`.

### Metadata in prompts/list

The `messages` array is stripped from the metadata returned by `prompts/list` — messages are only returned via `prompts/get` after rendering. This keeps discovery responses lightweight.

## Authoring Principles

### 1. Always identify Convex clearly

The word "Convex" can be ambiguous — there are other products, companies, and mathematical concepts with the same name. Every prompt persona should make clear we are talking about **Convex lattice technology** — the decentralised execution platform at [convex.world](https://convex.world).

Good: _"You are an expert on Convex accounts and the CVM (Convex Virtual Machine)."_

Bad: _"You are an account explorer."_

### 2. Include at least one documentation link

Every prompt must include at least one link to a relevant Convex resource, so the LLM can ground its knowledge and perform web searches if needed. Prefer valid links to `docs.convex.world`:

- Convex Lisp spec (CAD026): https://docs.convex.world/docs/cad/lisp
- Data encoding (CAD002): https://docs.convex.world/docs/cad/encoding
- Accounts and actors (CAD004): https://docs.convex.world/docs/cad/accounts
- Compiler (CAD008): https://docs.convex.world/docs/cad/compiler
- Docs hub: https://docs.convex.world
- Interactive sandbox: https://convex.world/sandbox

### 3. Teach, don't just instruct

The persona message should provide genuine Convex domain knowledge — not just "use tool X". LLMs may not know how Convex works. The persona should teach the specific concepts needed for the task:

- For account exploration: explain user accounts vs actors vs system accounts, what system account numbers mean, how CNS resolution works
- For deployment: explain the actor model, `deploy`/`export` semantics, controller accounts
- For transfers: explain copper/Gold units, juice costs, atomicity

This is the most important content in the prompt — it determines whether the LLM gives correct, Convex-specific answers or generic guesses.

### 4. Provide brief, relevant context

Include only the Convex knowledge relevant to the specific task. Don't dump the entire CVM specification into every prompt. The `convex-guide` prompt needs broad language knowledge; `transfer-funds` just needs to know about `transfer`, coin units, and juice.

### 5. Describe tools, don't assume them

The LLM may not have access to all tools listed. Frame tool references as what the Convex MCP server provides, and suggest the LLM check what it has:

Good: _"The Convex MCP server provides these tools (check which you have access to):"_

Bad: _"You have MCP tools available:"_

### 6. Keep request messages concise

The second user message (the actual request) should be short. The LLM already has the context from the persona — the request just needs to say what to do. Don't prescribe rigid output formats; LLMs can figure out appropriate presentation.

Good: _"Explore Convex account ${address}. Use describeAccount to get the full record, then lookup to examine key symbols."_

Bad: _"Present your findings as: - **Type**: User / Actor / System (and why) - **Address**: the account address - **Balance**: X copper (Y CVM Gold) ..."_ [20 lines of template]

### 7. Don't echo sensitive arguments

Passphrases and credentials should not appear in the rendered prompt text. The persona can explain that the user has provided a passphrase, but should not include its value.

### 8. Use British English

Consistent with the Convex project convention: decentralised, organised, behaviour, etc.

## Current Prompts

| Name | Title | Always? | Key Convex context | Doc links |
|------|-------|---------|-------------------|-----------|
| `explore-account` | Explore Account | Yes | Account types (user/actor/system), system account roles, CNS resolution | CAD004 |
| `network-status` | Network Status | Yes | CPoS consensus, peers, stake, memory exchange, health indicators | docs hub |
| `convex-guide` | Convex Lisp Guide | Yes | Full CVM language overview — types, syntax, special forms, actors, assets, system accounts | CAD026, CAD002, CAD004, CAD008, sandbox |
| `create-account` | Create Account | Signing | Ed25519 signing service, passphrase encryption, faucet limits | CAD004 |
| `deploy-contract` | Deploy Smart Contract | Signing | Actor model, deploy/export semantics, controller, common patterns | CAD004, CAD026 |
| `transfer-funds` | Transfer Coins | Signing | Transfer function, copper/Gold units, juice costs, atomicity | CAD026 |

## Adding a New Prompt

1. Create a JSON file in `src/main/resources/convex/restapi/mcp/prompts/`
2. Follow the three-message pattern: persona, request, prefill
3. Include the `name`, `title`, `description`, `arguments`, and `messages` fields
4. Write a persona that teaches the LLM the Convex knowledge it needs
5. Include at least one `docs.convex.world` link
6. Add registration in `McpPrompts.registerAll()`
7. Add test coverage in `McpPromptsTest`
8. Verify with `mvn test -pl convex-restapi -Dtest=McpPromptsTest`

## Testing

`McpPromptsTest` verifies:

- All prompts have required metadata fields (name, title, description, arguments)
- Messages array is excluded from `prompts/list` responses
- All prompts follow the persona + request + prefill pattern
- Argument substitution works (`${address}`, `${topic}`, etc.)
- Persona messages are substantial and teach Convex concepts
- Every prompt includes at least one documentation link
- Tools are described as available, not assumed
- Passphrases are not echoed in prompt text
- Error handling for unknown prompts and missing arguments
- Prompts capability is declared in `initialize` response
