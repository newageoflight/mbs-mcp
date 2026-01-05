# Medicare Australia MCP Server

An MCP (Model Context Protocol) server that provides access to the Australian Medicare Benefits Schedule (MBS). Query over 6,000 medical billing items with fees, descriptions, and categories directly from Claude or any MCP-compatible AI assistant.

## Features

- **6,000+ MBS Items**: Complete Medicare Benefits Schedule data
- **Multiple Search Methods**: By item number, description keywords, or category
- **Fee Information**: Schedule fees, benefits at 100%/85%/75%, derived fees
- **Category Browsing**: Navigate the MBS structure by category and group
- **Fee Comparison**: Compare multiple items side-by-side

## Installation

### For Claude Code

Add to your Claude Code MCP settings:

```json
{
  "mcpServers": {
    "medicare-aus": {
      "command": "npx",
      "args": ["-y", "medicare-aus-mcp"]
    }
  }
}
```

### For Claude Desktop

Add to your `claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "medicare-aus": {
      "command": "npx",
      "args": ["-y", "medicare-aus-mcp"]
    }
  }
}
```

### Manual Installation

```bash
npm install -g medicare-aus-mcp
```

## Available Tools

### `mbs_lookup`
Look up a specific MBS item by its item number.

```
mbs_lookup(item_number: "23")
```

Returns full details including fee, benefit amounts, and description.

### `mbs_search`
Search MBS items by keywords in their description.

```
mbs_search(query: "colonoscopy", limit: 10)
```

### `mbs_browse_category`
Browse items by category and/or group.

```
mbs_browse_category(category: "1", group: "A1", limit: 20)
```

### `mbs_list_categories`
List all available MBS categories and their groups.

```
mbs_list_categories()
```

### `mbs_fee_comparison`
Compare fees for multiple MBS items.

```
mbs_fee_comparison(item_numbers: ["23", "36", "44"])
```

### `mbs_stats`
Get statistics about the MBS data.

```
mbs_stats()
```

## MBS Categories

| Category | Description | Items |
|----------|-------------|-------|
| 1 | Professional Attendances | 661 |
| 2 | Diagnostic Procedures | 137 |
| 3 | Therapeutic Procedures | 3,403 |
| 4 | Oral and Maxillofacial | 228 |
| 5 | Diagnostic Imaging | 542 |
| 6 | Pathology | 619 |
| 7 | Cleft Lip and Palate | 46 |
| 8 | Miscellaneous | 293 |
| 10 | Telehealth | 76 |

## Example Usage

Once configured, you can ask Claude:

- "What is MBS item 23?"
- "Search for items related to knee surgery"
- "Compare fees for items 104, 105, and 106"
- "Show me all Category 3 Group T8 items"
- "What are the MBS statistics?"

## Data Source

MBS data is sourced from the Australian Government Department of Health. The included dataset is from January 2026.

## Development

```bash
# Clone the repository
git clone https://github.com/stephenwinters81/medicare-aus-mcp.git
cd medicare-aus-mcp

# Install dependencies
npm install

# Build
npm run build

# Run locally
npm start
```

## License

MIT

## Disclaimer

This tool is for informational purposes only. Always verify billing codes and fees with official Medicare documentation. The data may not reflect the most current MBS updates.
