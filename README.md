# BuildGPT

This mod adds a `/buildgpt` command that allows the player to place AI generated structures into the world with a customizable prompt.

It also runs an **MCP server**, so you can build with the Claude or ChatGPT/Codex subscription you already pay for instead of an API key. See [Build with your Claude or ChatGPT subscription (MCP)](#build-with-your-claude-or-chatgpt-subscription-mcp).

## Setup
An OpenAI Key is required for the `/buildgpt bound|unbound` commands (not for the MCP server).

create a file `.openaikey` in the config folder or run the `/buildbpt` command with valid parameters once to generate the file and then place your API Key in there.

The command should now work!

## Command
The command has 2 modes:
### Bound
`/buildgpt bound <x> <y> <z> <x> <y> <z> <prompt>`
This mode allows the user to set a bounding box outside of which the mod won't place any blocks.

The prompt is directing ChatGPT to generate a structure within those bounds but the mod also has a check after the response, that filters out any blocks outside the bounding box.

### Unbound
`/buildgpt unbound <x> <y> <z> <prompt>`
This mode only has a starting point from which the AI will (in most cases) generate the structure towards increasing coordinates.

Of course this is not always the case as ChatGPT can sometimes return funny responses with unexpected behaviour, which is why the `bound` mode exists and the `unbound` mode should by used with caution, since placed blocks cannot be undone. During testing, I noticed that structures will **usually** not exceed a 20x20x20 block cube unless prompted to do otherwhise.

## Prompt
There is a predefined prompt in the Mod, meaning that the user is only required to input the building name or description into the `<prompt>` field of the command, since the mod requires the response to be formatted a certain way.

The prompt can also be overwritten by inputting a custom prompt into the `<prompt>` field of the command.

## Build with your Claude or ChatGPT subscription (MCP)

When a world or server starts, the mod starts a local [MCP](https://modelcontextprotocol.io) server at
`http://127.0.0.1:25590/mcp`. AI apps that support MCP (Claude Code, Claude Desktop, Codex CLI) can connect to it
and build in your world. The AI runs on your subscription in that app, so no API key is needed.

You type what you want in the AI app (for example *"build a small medieval house with a garden next to me"*). The AI
looks at the terrain, places blocks, checks its work and fixes mistakes. Players see a chat message whenever it
changes blocks. Run `/buildgpt mcp` in game to check that the server is running.

### Tools the AI gets

| Tool | What it does |
|---|---|
| `get_world_info` | Online players (position, dimension, facing), dimensions and their height limits |
| `get_surface` | Height map of an area: top block height and type per column |
| `get_blocks` | Every block in a box (compact palette format) |
| `search_blocks` | Find block ids and their states (e.g. stair `facing`/`half`) |
| `place_blocks` | Place a list of blocks; ids are validated first, coordinates can be relative to an `origin` |
| `fill_region` | Fill a box (`replace`, `keep`, `outline`, `hollow`, optional `only_replace`); `minecraft:air` clears |
| `undo` | Revert the last edits (default history: 10 edits) |

### Setup

1. Start Minecraft with the mod and open a world once. This creates `config/buildgpt-mcp.properties`.
2. Copy the `token=` value from that file.
3. Connect your AI app (below), replacing `YOUR_TOKEN`.

**Claude Code** (Claude Pro/Max):
```sh
claude mcp add --transport http buildgpt http://127.0.0.1:25590/mcp --header "Authorization: Bearer YOUR_TOKEN"
```

**Claude Desktop** (Claude Pro/Max, needs [Node.js](https://nodejs.org)). Add to `claude_desktop_config.json`
(Settings → Developer → Edit Config) and restart Claude Desktop:
```json
{
  "mcpServers": {
    "buildgpt": {
      "command": "npx",
      "args": ["-y", "mcp-remote", "http://127.0.0.1:25590/mcp",
               "--header", "Authorization:${BUILDGPT_AUTH}", "--transport", "http-only"],
      "env": { "BUILDGPT_AUTH": "Bearer YOUR_TOKEN" }
    }
  }
}
```

**Codex CLI** (sign in with ChatGPT Plus/Pro). Add to `~/.codex/config.toml` and set the environment variable
`BUILDGPT_MCP_TOKEN=YOUR_TOKEN`:
```toml
[mcp_servers.buildgpt]
url = "http://127.0.0.1:25590/mcp"
bearer_token_env_var = "BUILDGPT_MCP_TOKEN"
```
If your Codex version doesn't support HTTP servers, use the same `npx mcp-remote` bridge as Claude Desktop
(`command = "npx"`, `args = [...]`, `env = {...}`).

**ChatGPT / claude.ai in the browser**: their connectors are called from the provider's cloud, so they cannot
reach `127.0.0.1`. You would need a public HTTPS tunnel to your PC, which exposes your world to the internet.
Use Claude Desktop, Claude Code or Codex instead.

### Settings (`config/buildgpt-mcp.properties`)

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `true` | Start the MCP server |
| `host` | `127.0.0.1` | Address to listen on. Only change this if you understand the security impact |
| `port` | `25590` | Port to listen on |
| `token` | random | Clients must send `Authorization: Bearer <token>`. Empty disables auth (not recommended) |
| `announce_in_chat` | `true` | Tell players in chat when the AI changes blocks |
| `max_blocks_per_call` | `10000` | Max blocks in one `place_blocks` call |
| `max_fill_volume` | `131072` | Max box volume for `fill_region` |
| `max_read_volume` | `32768` | Max box volume / area for `get_blocks` and `get_surface` |
| `undo_history` | `10` | How many edits `undo` can revert |

Changes apply the next time a world or server starts.

### Notes
- Anyone with the token who can reach the port can edit the world as if they were an operator. Keep the token
  private and keep `host=127.0.0.1` unless you have a reason not to.
- On a dedicated server the MCP server runs on the server machine, so the AI app must run there too (or reach it
  through an SSH tunnel).
- `undo` restores blocks and their states but not container contents (chest items etc.).
- Large builds use more of your subscription's usage limits. The AI uses `fill_region` for big areas to save on that.
