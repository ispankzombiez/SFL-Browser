// SFL Browser - Scriptable iOS Widget
// Optional: override this by setting the widget parameter in Scriptable.
const USER_ID = "__USER_ID__";

const API_BASE = "https://api.sunflower-land.com";
const REQUEST_TIMEOUT_SECONDS = 12;

const CROP_GROWTH_TIMES = {
  Sunflower: 1 * 60 * 1000,
  Potato: 5 * 60 * 1000,
  Rhubarb: 10 * 60 * 1000,
  Pumpkin: 30 * 60 * 1000,
  Zucchini: 30 * 60 * 1000,
  Carrot: 60 * 60 * 1000,
  Yam: 60 * 60 * 1000,
  Cabbage: 2 * 60 * 60 * 1000,
  Broccoli: 2 * 60 * 60 * 1000,
  Soybean: 3 * 60 * 60 * 1000,
  Beetroot: 4 * 60 * 60 * 1000,
  Pepper: 4 * 60 * 60 * 1000,
  Cauliflower: 8 * 60 * 60 * 1000,
  Parsnip: 12 * 60 * 60 * 1000,
  Eggplant: 16 * 60 * 60 * 1000,
  Corn: 20 * 60 * 60 * 1000,
  Onion: 20 * 60 * 60 * 1000,
  Radish: 24 * 60 * 60 * 1000,
  Wheat: 24 * 60 * 60 * 1000,
  Turnip: 24 * 60 * 60 * 1000,
  Kale: 36 * 60 * 60 * 1000,
  Artichoke: 36 * 60 * 60 * 1000,
  Barley: 48 * 60 * 60 * 1000,
};

const FLOWER_GROWTH_TIMES = {
  "Red Pansy": 30 * 60 * 1000,
  "Yellow Pansy": 30 * 60 * 1000,
  "Purple Pansy": 30 * 60 * 1000,
  "White Pansy": 30 * 60 * 1000,
  "Blue Pansy": 30 * 60 * 1000,
  "Red Cosmos": 60 * 60 * 1000,
  "Yellow Cosmos": 60 * 60 * 1000,
  "Purple Cosmos": 60 * 60 * 1000,
  "White Cosmos": 60 * 60 * 1000,
  "Blue Cosmos": 60 * 60 * 1000,
};

const RESOURCE_GROWTH_TIMES = {
  tree: 2 * 60 * 60 * 1000,
  stone: 4 * 60 * 60 * 1000,
  iron: 8 * 60 * 60 * 1000,
  gold: 24 * 60 * 60 * 1000,
  crimstone: 24 * 60 * 60 * 1000,
  sunstone: 3 * 24 * 60 * 60 * 1000,
  oil: 20 * 60 * 60 * 1000,
};

const FRUIT_GROWTH_TIMES = {
  Tomato: 2 * 60 * 60 * 1000,
  Lemon: 4 * 60 * 60 * 1000,
  Blueberry: 6 * 60 * 60 * 1000,
  Orange: 8 * 60 * 60 * 1000,
  Apple: 12 * 60 * 60 * 1000,
  Banana: 12 * 60 * 60 * 1000,
};

function normalizeTimestamp(ts) {
  if (!ts && ts !== 0) return null;
  if (typeof ts === "number") return ts < 1e12 ? ts * 1000 : ts;
  const n = Number(ts);
  if (!Number.isFinite(n)) return null;
  return n < 1e12 ? n * 1000 : n;
}

function resolveUserId() {
  const widgetParam = typeof args.widgetParameter === "string" ? args.widgetParameter.trim() : "";
  const fallback = (USER_ID || "").trim();
  const resolved = widgetParam || fallback;
  return resolved === "__USER_ID__" ? "" : resolved;
}

function formatCountdown(msRemaining) {
  const total = Math.max(0, Math.floor(msRemaining / 1000));
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  if (h > 0) return `${h}h ${m}m`;
  if (m > 0) return `${m}m ${s}s`;
  return `${s}s`;
}

async function webServiceGetJSON(url) {
  const req = new Request(url);
  req.timeoutInterval = REQUEST_TIMEOUT_SECONDS;
  req.method = "GET";
  req.headers = {
    "User-Agent": "SFL-Browser-Scriptable-Widget/1.0",
    "Cache-Control": "no-cache",
  };
  return await req.loadJSON();
}

async function fetchFarmData(userId) {
  const endpoints = [
    `${API_BASE}/community/farms/${encodeURIComponent(userId)}`,
    `${API_BASE}/community/farm/${encodeURIComponent(userId)}`,
    `${API_BASE}/farm/${encodeURIComponent(userId)}`,
    `${API_BASE}/farms/${encodeURIComponent(userId)}`,
  ];

  let lastError = null;
  for (const url of endpoints) {
    try {
      const payload = await webServiceGetJSON(url);
      const farm = payload?.farm || payload?.data?.farm || payload?.state?.farm || payload?.game?.farm || payload;
      if (farm && typeof farm === "object") {
        return { farm, raw: payload };
      }
    } catch (error) {
      lastError = error;
    }
  }

  throw new Error(lastError ? String(lastError) : "Unable to load farm data.");
}

function collectTimers(farm) {
  const now = Date.now();
  const timers = [];

  if (farm?.crops) {
    for (const [, plot] of Object.entries(farm.crops)) {
      const crop = plot?.crop;
      if (!crop?.name || !crop?.plantedAt) continue;
      const plantedAt = normalizeTimestamp(crop.plantedAt);
      const growth = CROP_GROWTH_TIMES[crop.name] || 60 * 60 * 1000;
      if (!plantedAt) continue;
      timers.push({ label: `🌾 ${crop.name}`, readyAt: plantedAt + growth });
    }
  }

  const flowerBeds = farm?.flowers?.flowerBeds || {};
  for (const [, bed] of Object.entries(flowerBeds)) {
    const flower = bed?.flower;
    if (!flower?.name || !flower?.plantedAt) continue;
    const plantedAt = normalizeTimestamp(flower.plantedAt);
    const growth = FLOWER_GROWTH_TIMES[flower.name] || 2 * 60 * 60 * 1000;
    if (!plantedAt) continue;
    timers.push({ label: `🌸 ${flower.name}`, readyAt: plantedAt + growth });
  }

  if (farm?.fruitPatches) {
    for (const [, patch] of Object.entries(farm.fruitPatches)) {
      const fruit = patch?.fruit;
      if (!fruit?.name) continue;
      const harvestedAt = normalizeTimestamp(fruit.harvestedAt);
      const plantedAt = normalizeTimestamp(fruit.plantedAt);
      const growth = FRUIT_GROWTH_TIMES[fruit.name] || 6 * 60 * 60 * 1000;
      const readyAt = harvestedAt || (plantedAt ? plantedAt + growth : null);
      if (!readyAt) continue;
      timers.push({ label: `🍎 ${fruit.name}`, readyAt });
    }
  }

  const cookingBuildings = ["Fire Pit", "Bakery", "Kitchen", "Deli", "Smoothie Shack"];
  for (const name of cookingBuildings) {
    const buildings = farm?.buildings?.[name] || [];
    for (const building of buildings) {
      const crafting = building?.crafting || [];
      for (const item of crafting) {
        const readyAt = normalizeTimestamp(item?.readyAt);
        if (!readyAt || !item?.name) continue;
        timers.push({ label: `🍳 ${item.name}`, readyAt });
      }
    }
  }

  const composterBuildings = ["Compost Bin", "Turbo Composter", "Premium Composter"];
  for (const name of composterBuildings) {
    const buildings = farm?.buildings?.[name] || [];
    for (const building of buildings) {
      const readyAt = normalizeTimestamp(building?.producing?.readyAt);
      if (!readyAt) continue;
      timers.push({ label: `♻️ ${name}`, readyAt });
    }
  }

  if (farm?.trees) {
    for (const [, tree] of Object.entries(farm.trees)) {
      const choppedAt = normalizeTimestamp(tree?.wood?.choppedAt);
      if (!choppedAt) continue;
      timers.push({ label: "🌲 Tree", readyAt: choppedAt + RESOURCE_GROWTH_TIMES.tree });
    }
  }

  const mapResource = [
    ["stones", "stone", "🪨 Stone"],
    ["iron", "iron", "⛓️ Iron"],
    ["gold", "gold", "🥇 Gold"],
    ["crimstones", "crimstone", "🧱 Crimstone"],
    ["sunstones", "sunstone", "☀️ Sunstone"],
  ];
  for (const [field, key, label] of mapResource) {
    const entries = farm?.[field] || {};
    for (const [, item] of Object.entries(entries)) {
      const minedAt = normalizeTimestamp(item?.stone?.minedAt);
      if (!minedAt) continue;
      timers.push({ label, readyAt: minedAt + RESOURCE_GROWTH_TIMES[key] });
    }
  }

  if (farm?.oilReserves) {
    for (const [, oil] of Object.entries(farm.oilReserves)) {
      const drilledAt = normalizeTimestamp(oil?.oil?.drilledAt);
      if (!drilledAt) continue;
      timers.push({ label: "🛢️ Oil", readyAt: drilledAt + RESOURCE_GROWTH_TIMES.oil });
    }
  }

  const readyNow = timers.filter((t) => t.readyAt <= now);
  const upcoming = timers.filter((t) => t.readyAt > now).sort((a, b) => a.readyAt - b.readyAt);
  return { readyNow, upcoming, totalTracked: timers.length };
}

function money(v, maxDigits = 2) {
  const n = Number(v || 0);
  if (!Number.isFinite(n)) return "0";
  return n.toLocaleString(undefined, { maximumFractionDigits: maxDigits });
}

function createErrorWidget(title, subtitle) {
  const widget = new ListWidget();
  widget.backgroundColor = new Color("#1b1f2a");
  widget.setPadding(12, 12, 12, 12);
  const t = widget.addText(title);
  t.font = Font.boldSystemFont(14);
  t.textColor = Color.white();
  widget.addSpacer(6);
  const s = widget.addText(subtitle);
  s.font = Font.systemFont(12);
  s.textColor = new Color("#d6d8de");
  return widget;
}

function buildWidget(userId, farm, timers) {
  const widget = new ListWidget();
  widget.setPadding(12, 12, 12, 12);
  const gradient = new LinearGradient();
  gradient.colors = [new Color("#1b1f2a"), new Color("#242b3a")];
  gradient.locations = [0, 1];
  widget.backgroundGradient = gradient;

  const title = widget.addText(`🌻 SFL Farm #${userId}`);
  title.font = Font.boldSystemFont(14);
  title.textColor = Color.white();

  widget.addSpacer(6);

  const coins = widget.addText(`Coins: ${money(farm?.coins)}`);
  coins.font = Font.mediumSystemFont(12);
  coins.textColor = new Color("#ffd46b");

  const balance = widget.addText(`$FLOWER: ${money(farm?.balance, 4)}`);
  balance.font = Font.mediumSystemFont(12);
  balance.textColor = new Color("#8ce6ad");

  const inventoryCount = Object.keys(farm?.inventory || {}).length;
  const inv = widget.addText(`Inventory slots: ${inventoryCount}`);
  inv.font = Font.mediumSystemFont(11);
  inv.textColor = new Color("#c5cad4");

  widget.addSpacer(8);

  const ready = widget.addText(`Ready now: ${timers.readyNow.length}`);
  ready.font = Font.boldSystemFont(12);
  ready.textColor = timers.readyNow.length > 0 ? new Color("#7ef2a5") : new Color("#c5cad4");

  const first = timers.upcoming[0];
  if (first) {
    const next = widget.addText(`Next: ${first.label} in ${formatCountdown(first.readyAt - Date.now())}`);
    next.font = Font.systemFont(11);
    next.textColor = Color.white();
  } else {
    const next = widget.addText("Next: no active timers found");
    next.font = Font.systemFont(11);
    next.textColor = new Color("#c5cad4");
  }

  if (config.widgetFamily === "large") {
    widget.addSpacer(8);
    const section = widget.addText("Upcoming");
    section.font = Font.boldSystemFont(12);
    section.textColor = new Color("#ffd46b");

    const upcoming = timers.upcoming.slice(0, 5);
    for (const item of upcoming) {
      const line = widget.addText(`• ${item.label} · ${formatCountdown(item.readyAt - Date.now())}`);
      line.font = Font.systemFont(10);
      line.textColor = new Color("#e6e8ee");
    }
  }

  return widget;
}

async function run() {
  const userId = resolveUserId();
  if (!userId) {
    return createErrorWidget(
      "Missing User/Farm ID",
      'Set `USER_ID` in the script or pass it via Scriptable widget parameter.',
    );
  }

  try {
    const { farm } = await fetchFarmData(userId);
    const timers = collectTimers(farm);
    return buildWidget(userId, farm, timers);
  } catch (error) {
    return createErrorWidget("Unable to load farm", String(error).slice(0, 120));
  }
}

const widget = await run();
Script.setWidget(widget);
if (!config.runsInWidget) {
  await widget.presentMedium();
}
Script.complete();
