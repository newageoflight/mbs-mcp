#!/usr/bin/env node

import { Server } from "@modelcontextprotocol/sdk/server/index.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import {
  CallToolRequestSchema,
  ListToolsRequestSchema,
} from "@modelcontextprotocol/sdk/types.js";
import { XMLParser } from "fast-xml-parser";
import { readFileSync } from "fs";
import { fileURLToPath } from "url";
import { dirname, join } from "path";

// Types for MBS data
interface MBSItem {
  ItemNum: string;
  SubItemNum?: string;
  ItemStartDate?: string;
  ItemEndDate?: string;
  Category: string;
  Group: string;
  SubGroup?: string;
  SubHeading?: string;
  ItemType?: string;
  FeeType?: string;
  ProviderType?: string;
  NewItem?: string;
  ItemChange?: string;
  AnaesChange?: string;
  DescriptorChange?: string;
  FeeChange?: string;
  EMSNChange?: string;
  EMSNCap?: string;
  BenefitType?: string;
  BenefitStartDate?: string;
  FeeStartDate?: string;
  ScheduleFee?: string;
  Benefit100?: string;
  Benefit75?: string;
  Benefit85?: string;
  BasicUnits?: string;
  EMSNStartDate?: string;
  EMSNEndDate?: string;
  EMSNFixedCapAmount?: string;
  EMSNMaximumCap?: string;
  EMSNPercentageCap?: string;
  EMSNDescription?: string;
  EMSNChangeDate?: string;
  DerivedFeeStartDate?: string;
  DerivedFee?: string;
  DescriptionStartDate?: string;
  Description: string;
  QFEStartDate?: string;
  QFEEndDate?: string;
}

interface MBSData {
  MBS_XML: {
    Data: MBSItem[];
  };
}

// Global data store
let mbsItems: MBSItem[] = [];
let categories: Map<string, Set<string>> = new Map(); // category -> groups

function loadMBSData(): void {
  const __filename = fileURLToPath(import.meta.url);
  const __dirname = dirname(__filename);

  // Look for XML file in multiple locations
  const possiblePaths = [
    join(__dirname, "..", "mbs-xml-20260101.xml"),
    join(__dirname, "..", "..", "mbs-xml-20260101.xml"),
    join(process.cwd(), "mbs-xml-20260101.xml"),
  ];

  let xmlData: string | null = null;
  let loadedPath: string | null = null;

  for (const path of possiblePaths) {
    try {
      xmlData = readFileSync(path, "utf-8");
      loadedPath = path;
      break;
    } catch {
      continue;
    }
  }

  if (!xmlData) {
    throw new Error(
      `Could not find mbs-xml-20260101.xml in any of: ${possiblePaths.join(", ")}`
    );
  }

  const parser = new XMLParser({
    ignoreAttributes: true,
    trimValues: true,
  });

  const parsed: MBSData = parser.parse(xmlData);
  mbsItems = Array.isArray(parsed.MBS_XML.Data)
    ? parsed.MBS_XML.Data
    : [parsed.MBS_XML.Data];

  // Build category index
  for (const item of mbsItems) {
    const cat = item.Category || "Unknown";
    if (!categories.has(cat)) {
      categories.set(cat, new Set());
    }
    if (item.Group) {
      categories.get(cat)!.add(item.Group);
    }
  }

  console.error(`Loaded ${mbsItems.length} MBS items from ${loadedPath}`);
}

function formatItem(item: MBSItem): string {
  const lines: string[] = [];
  lines.push(`Item ${item.ItemNum}${item.SubItemNum ? `.${item.SubItemNum}` : ""}`);
  lines.push(`Category: ${item.Category} | Group: ${item.Group}${item.SubGroup ? ` | SubGroup: ${item.SubGroup}` : ""}`);

  if (item.ScheduleFee) {
    lines.push(`Schedule Fee: $${item.ScheduleFee}`);
  }
  if (item.Benefit100) {
    lines.push(`Benefit (100%): $${item.Benefit100}`);
  }
  if (item.Benefit85) {
    lines.push(`Benefit (85%): $${item.Benefit85}`);
  }
  if (item.Benefit75) {
    lines.push(`Benefit (75%): $${item.Benefit75}`);
  }
  if (item.DerivedFee) {
    lines.push(`Derived Fee: ${item.DerivedFee}`);
  }
  if (item.FeeStartDate) {
    lines.push(`Fee Start Date: ${item.FeeStartDate}`);
  }
  if (item.ItemStartDate) {
    lines.push(`Item Start Date: ${item.ItemStartDate}`);
  }
  if (item.ItemEndDate) {
    lines.push(`Item End Date: ${item.ItemEndDate}`);
  }

  lines.push("");
  lines.push("Description:");
  lines.push(item.Description);

  if (item.EMSNMaximumCap) {
    lines.push("");
    lines.push(`EMSN Maximum Cap: $${item.EMSNMaximumCap}`);
  }
  if (item.EMSNDescription) {
    lines.push(`EMSN Description: ${item.EMSNDescription}`);
  }

  return lines.join("\n");
}

function formatItemSummary(item: MBSItem): string {
  const fee = item.ScheduleFee ? `$${item.ScheduleFee}` : (item.DerivedFee ? "Derived" : "N/A");
  const desc = item.Description.length > 100
    ? item.Description.substring(0, 100) + "..."
    : item.Description;
  return `${item.ItemNum}: ${fee} - ${desc}`;
}

// Create MCP server
const server = new Server(
  {
    name: "medicare-aus-mcp",
    version: "1.0.0",
  },
  {
    capabilities: {
      tools: {},
    },
  }
);

// List available tools
server.setRequestHandler(ListToolsRequestSchema, async () => {
  return {
    tools: [
      {
        name: "mbs_lookup",
        description: "Look up a specific MBS item by its item number. Returns full details including fee, benefit, and description.",
        inputSchema: {
          type: "object",
          properties: {
            item_number: {
              type: "string",
              description: "The MBS item number to look up (e.g., '23', '104', '36')",
            },
          },
          required: ["item_number"],
        },
      },
      {
        name: "mbs_search",
        description: "Search MBS items by keywords in their description. Returns matching items with summaries.",
        inputSchema: {
          type: "object",
          properties: {
            query: {
              type: "string",
              description: "Search keywords to find in item descriptions (e.g., 'consultation', 'surgery', 'anaesthesia')",
            },
            limit: {
              type: "number",
              description: "Maximum number of results to return (default: 20)",
            },
          },
          required: ["query"],
        },
      },
      {
        name: "mbs_browse_category",
        description: "Browse MBS items by category and/or group. Use mbs_list_categories first to see available categories.",
        inputSchema: {
          type: "object",
          properties: {
            category: {
              type: "string",
              description: "Category number (e.g., '1', '2', '3')",
            },
            group: {
              type: "string",
              description: "Group code within the category (e.g., 'A1', 'A2', 'T8')",
            },
            limit: {
              type: "number",
              description: "Maximum number of results to return (default: 20)",
            },
          },
          required: ["category"],
        },
      },
      {
        name: "mbs_list_categories",
        description: "List all available MBS categories and their groups. Useful for browsing the MBS structure.",
        inputSchema: {
          type: "object",
          properties: {},
        },
      },
      {
        name: "mbs_fee_comparison",
        description: "Compare fees for multiple MBS items. Useful for comparing similar services.",
        inputSchema: {
          type: "object",
          properties: {
            item_numbers: {
              type: "array",
              items: { type: "string" },
              description: "Array of MBS item numbers to compare",
            },
          },
          required: ["item_numbers"],
        },
      },
      {
        name: "mbs_stats",
        description: "Get statistics about the loaded MBS data including total items, categories, and fee ranges.",
        inputSchema: {
          type: "object",
          properties: {},
        },
      },
    ],
  };
});

// Handle tool calls
server.setRequestHandler(CallToolRequestSchema, async (request) => {
  const { name, arguments: args } = request.params;

  switch (name) {
    case "mbs_lookup": {
      const itemNum = String(args?.item_number || "");
      const item = mbsItems.find(
        (i) => String(i.ItemNum) === itemNum || String(i.ItemNum) === itemNum.padStart(2, "0")
      );

      if (!item) {
        return {
          content: [
            {
              type: "text",
              text: `No MBS item found with number: ${itemNum}`,
            },
          ],
        };
      }

      return {
        content: [
          {
            type: "text",
            text: formatItem(item),
          },
        ],
      };
    }

    case "mbs_search": {
      const query = String(args?.query || "").toLowerCase();
      const limit = Number(args?.limit) || 20;

      const matches = mbsItems
        .filter((item) => item.Description.toLowerCase().includes(query))
        .slice(0, limit);

      if (matches.length === 0) {
        return {
          content: [
            {
              type: "text",
              text: `No MBS items found matching: "${query}"`,
            },
          ],
        };
      }

      const results = matches.map(formatItemSummary).join("\n\n");
      return {
        content: [
          {
            type: "text",
            text: `Found ${matches.length} items matching "${query}":\n\n${results}`,
          },
        ],
      };
    }

    case "mbs_browse_category": {
      const category = String(args?.category || "");
      const group = args?.group ? String(args.group) : undefined;
      const limit = Number(args?.limit) || 20;

      let matches = mbsItems.filter((item) => item.Category === category);

      if (group) {
        matches = matches.filter((item) => item.Group === group);
      }

      matches = matches.slice(0, limit);

      if (matches.length === 0) {
        return {
          content: [
            {
              type: "text",
              text: `No MBS items found in category ${category}${group ? ` group ${group}` : ""}`,
            },
          ],
        };
      }

      const results = matches.map(formatItemSummary).join("\n\n");
      return {
        content: [
          {
            type: "text",
            text: `Found ${matches.length} items in category ${category}${group ? ` group ${group}` : ""}:\n\n${results}`,
          },
        ],
      };
    }

    case "mbs_list_categories": {
      const lines: string[] = ["MBS Categories and Groups:", ""];

      const sortedCategories = Array.from(categories.keys()).sort(
        (a, b) => Number(a) - Number(b)
      );

      for (const cat of sortedCategories) {
        const groups = Array.from(categories.get(cat)!).sort();
        const itemCount = mbsItems.filter((i) => i.Category === cat).length;
        lines.push(`Category ${cat} (${itemCount} items):`);
        lines.push(`  Groups: ${groups.join(", ")}`);
        lines.push("");
      }

      return {
        content: [
          {
            type: "text",
            text: lines.join("\n"),
          },
        ],
      };
    }

    case "mbs_fee_comparison": {
      const itemNumbers = (args?.item_numbers as string[]) || [];

      if (itemNumbers.length === 0) {
        return {
          content: [
            {
              type: "text",
              text: "Please provide at least one item number to compare.",
            },
          ],
        };
      }

      const lines: string[] = ["MBS Fee Comparison:", ""];
      lines.push("Item | Schedule Fee | Benefit 100% | Benefit 85% | Benefit 75%");
      lines.push("-----|--------------|--------------|-------------|------------");

      for (const num of itemNumbers) {
        const item = mbsItems.find((i) => String(i.ItemNum) === String(num));
        if (item) {
          const fee = item.ScheduleFee ? `$${item.ScheduleFee}` : "Derived";
          const b100 = item.Benefit100 ? `$${item.Benefit100}` : "-";
          const b85 = item.Benefit85 ? `$${item.Benefit85}` : "-";
          const b75 = item.Benefit75 ? `$${item.Benefit75}` : "-";
          lines.push(`${num} | ${fee} | ${b100} | ${b85} | ${b75}`);
        } else {
          lines.push(`${num} | Not found | - | - | -`);
        }
      }

      return {
        content: [
          {
            type: "text",
            text: lines.join("\n"),
          },
        ],
      };
    }

    case "mbs_stats": {
      const totalItems = mbsItems.length;
      const categoryCount = categories.size;

      const feesItems = mbsItems.filter((i) => i.ScheduleFee);
      const fees = feesItems.map((i) => parseFloat(i.ScheduleFee!));
      const minFee = Math.min(...fees);
      const maxFee = Math.max(...fees);
      const avgFee = fees.reduce((a, b) => a + b, 0) / fees.length;

      const lines: string[] = [
        "MBS Data Statistics:",
        "",
        `Total Items: ${totalItems}`,
        `Categories: ${categoryCount}`,
        `Items with Schedule Fees: ${feesItems.length}`,
        "",
        "Fee Range:",
        `  Minimum: $${minFee.toFixed(2)}`,
        `  Maximum: $${maxFee.toFixed(2)}`,
        `  Average: $${avgFee.toFixed(2)}`,
        "",
        "Items per Category:",
      ];

      const sortedCategories = Array.from(categories.keys()).sort(
        (a, b) => Number(a) - Number(b)
      );

      for (const cat of sortedCategories) {
        const count = mbsItems.filter((i) => i.Category === cat).length;
        lines.push(`  Category ${cat}: ${count} items`);
      }

      return {
        content: [
          {
            type: "text",
            text: lines.join("\n"),
          },
        ],
      };
    }

    default:
      return {
        content: [
          {
            type: "text",
            text: `Unknown tool: ${name}`,
          },
        ],
        isError: true,
      };
  }
});

// Main entry point
async function main() {
  try {
    loadMBSData();
  } catch (error) {
    console.error("Failed to load MBS data:", error);
    process.exit(1);
  }

  const transport = new StdioServerTransport();
  await server.connect(transport);
  console.error("Medicare Australia MCP server running on stdio");
}

main().catch((error) => {
  console.error("Fatal error:", error);
  process.exit(1);
});
