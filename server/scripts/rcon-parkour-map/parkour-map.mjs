import { Rcon } from 'rcon-client';

const player = 'OSheepka';
const password = process.env.RCON_PASSWORD ?? '';
const sleep = (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds));
const numeric = '[-+]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?';

function vectorFrom(reply, dimensions) {
  const expression = dimensions === 3
    ? new RegExp(`\\[\\s*(${numeric})[df]?\\s*,\\s*(${numeric})[df]?\\s*,\\s*(${numeric})[df]?\\s*\\]`)
    : new RegExp(`\\[\\s*(${numeric})[df]?\\s*,\\s*(${numeric})[df]?\\s*\\]`);
  const match = reply.match(expression);
  if (!match) throw new Error(`Could not read the player's ${dimensions === 3 ? 'position' : 'rotation'} from RCON.`);
  return match.slice(1).map(Number);
}

if (!password || password.trim() !== password || !/^[A-Za-z0-9]+$/.test(password)) {
  console.error('RCON_PASSWORD must be non-empty ASCII letters/digits without surrounding whitespace.');
  process.exit(1);
}

let rcon;
try {
  const connectDeadline = Date.now() + 120_000;
  while (!rcon) {
    try {
      rcon = await Rcon.connect({ host: '::1', port: 25575, password, connectTimeout: 8000, timeout: 20_000, reconnect: false });
    } catch (error) {
      if (error?.code !== 'CONNECTION_FAILED' || Date.now() >= connectDeadline) throw error;
      console.log('Waiting for local RCON to become ready; retrying in 5 seconds.');
      await sleep(5000);
    }
  }
  console.log('RCON connected; this action does not restart the server.');

  const onlinePattern = new RegExp(`(?<![A-Za-z0-9_])${player}(?![A-Za-z0-9_])`, 'i');
  const onlineDeadline = Date.now() + 120_000;
  let isOnline = false;
  while (!isOnline) {
    isOnline = onlinePattern.test(await rcon.send('list'));
    if (!isOnline) {
      if (Date.now() >= onlineDeadline) throw new Error(`${player} did not join; parkour map build cancelled.`);
      console.log(`Waiting for ${player} to join; retrying in 5 seconds.`);
      await sleep(5000);
    }
  }

  const [playerX, playerY, playerZ] = vectorFrom(await rcon.send(`data get entity ${player} Pos`), 3);
  const [yaw] = vectorFrom(await rcon.send(`data get entity ${player} Rotation`), 2);
  const directionIndex = ((Math.round(yaw / 90) % 4) + 4) % 4;
  const directions = [
    { name: 'south', fx: 0, fz: 1 },
    { name: 'west', fx: -1, fz: 0 },
    { name: 'north', fx: 0, fz: -1 },
    { name: 'east', fx: 1, fz: 0 },
  ];
  const direction = directions[directionIndex];
  const rx = -direction.fz;
  const rz = direction.fx;

  // Exact 50x50 bounds: local u=0..49, v=0..49, beginning 50 blocks ahead.
  const originX = Math.floor(playerX + direction.fx * 50 - rx * 24);
  const originZ = Math.floor(playerZ + direction.fz * 50 - rz * 24);
  const baseY = Math.floor(playerY);
  const world = (u, v) => ({ x: originX + rx * u + direction.fx * v, z: originZ + rz * u + direction.fz * v });

  const send = async (label, command) => {
    const reply = await rcon.send(command);
    if (/unknown or incomplete command|invalid block|invalid argument|expected .* at position|could not set block/i.test(reply)) {
      throw new Error(`${label} failed: ${reply}`);
    }
    console.log(`${label}: ${reply}`);
  };

  const platforms = [];
  const add = (u, v, height, kind = 'stone') => {
    const previous = platforms.at(-1);
    if (previous && Math.abs(previous.height - height) > 1) {
      throw new Error(`Route design is not passable between platform heights ${previous.height} and ${height}.`);
    }
    platforms.push({ u, v, height, kind });
  };

  // Lane 1: serpentine route from the start to the far side.
  [
    [5, 1], [10, 1], [15, 2], [20, 2], [25, 3], [30, 2], [35, 3], [40, 3], [45, 4],
  ].forEach(([v, h], index) => add(5, v, h, index === 0 ? 'start' : 'stone'));
  add(9, 45, 4); add(13, 45, 4); add(15, 45, 4, 'checkpoint-cyan');

  // Lane 2 returns toward the player.
  [[40, 3], [35, 4], [30, 3], [25, 4], [20, 3], [15, 2], [10, 2], [5, 1]].forEach(([v, h]) => add(15, v, h));
  add(19, 5, 1); add(23, 5, 1); add(25, 5, 1, 'checkpoint-yellow');

  // Lane 3 runs forward again.
  [[10, 2], [15, 3], [20, 3], [25, 4], [30, 4], [35, 5], [40, 5], [45, 6]].forEach(([v, h]) => add(25, v, h));
  add(29, 45, 6); add(33, 45, 6); add(35, 45, 6, 'checkpoint-purple');

  // Lane 4 returns, then lane 5 finishes at the far corner.
  [[40, 5], [35, 5], [30, 4], [25, 4], [20, 3], [15, 3], [10, 2], [5, 2]].forEach(([v, h]) => add(35, v, h));
  add(39, 5, 2); add(43, 5, 2); add(45, 5, 2, 'checkpoint-orange');
  [[10, 3], [15, 3], [20, 4], [25, 4], [30, 5], [35, 5], [40, 6], [45, 6]].forEach(([v, h], index, list) =>
    add(45, v, h, index === list.length - 1 ? 'finish' : 'stone'));

  const blocks = {
    start: 'minecraft:lime_concrete',
    stone: 'minecraft:smooth_stone',
    'checkpoint-cyan': 'minecraft:cyan_concrete',
    'checkpoint-yellow': 'minecraft:yellow_concrete',
    'checkpoint-purple': 'minecraft:purple_concrete',
    'checkpoint-orange': 'minecraft:orange_concrete',
    finish: 'minecraft:gold_block',
  };
  const sizes = { start: 5, finish: 5, 'checkpoint-cyan': 5, 'checkpoint-yellow': 5, 'checkpoint-purple': 5, 'checkpoint-orange': 5 };

  console.log(`Building a medium 50x50 parkour course facing ${direction.name}; gaps are 1-2 blocks and height changes are at most 1 block.`);
  console.log('Only air at course-platform positions is filled; existing terrain and structures are not replaced.');

  // Four corner beacons make the 50x50 map bounds visible from the start.
  for (const [u, v] of [[0, 0], [49, 0], [0, 49], [49, 49]]) {
    const point = world(u, v);
    await send('Map-corner beacon', `fill ${point.x} ${baseY + 1} ${point.z} ${point.x} ${baseY + 3} ${point.z} minecraft:quartz_pillar[axis=y] replace minecraft:air`);
    await send('Beacon light', `setblock ${point.x} ${baseY + 4} ${point.z} minecraft:sea_lantern`);
  }

  for (let index = 0; index < platforms.length; index += 1) {
    const { u, v, height, kind } = platforms[index];
    const width = sizes[kind] ?? 3;
    const y = baseY + height - 1;
    const pointA = world(u - Math.floor(width / 2), v - Math.floor(width / 2));
    const pointB = world(u + Math.floor(width / 2), v + Math.floor(width / 2));
    const minX = Math.min(pointA.x, pointB.x);
    const maxX = Math.max(pointA.x, pointB.x);
    const minZ = Math.min(pointA.z, pointB.z);
    const maxZ = Math.max(pointA.z, pointB.z);
    await send(`Platform ${index + 1}/${platforms.length} (${kind})`, `fill ${minX} ${y} ${minZ} ${maxX} ${y} ${maxZ} ${blocks[kind]} replace minecraft:air`);

    const center = world(u, v);
    await send('Platform marker', `setblock ${center.x} ${y} ${center.z} ${kind === 'finish' ? 'minecraft:emerald_block' : 'minecraft:sea_lantern'}`);
  }

  console.log(`Parkour map completed: ${platforms.length} connected platforms, 50x50 bounds, finish at the far end.`);
} catch (error) {
  console.error(`Parkour build failed (${error?.code ?? error?.name ?? 'unknown'}): ${error?.message ?? 'no details'}`);
  process.exitCode = 1;
} finally {
  if (rcon) {
    try {
      await rcon.end();
    } catch {
      // Best-effort close; preserve the original result.
    }
  }
}
