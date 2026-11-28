import { Rcon } from 'rcon-client';

const player = 'OSheepka';
const password = process.env.RCON_PASSWORD ?? '';
const sleep = (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds));
const numberPattern = '[-+]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?';

function readVector(reply, size) {
  const vectorPattern = size === 3
    ? new RegExp(`\\[\\s*(${numberPattern})[df]?\\s*,\\s*(${numberPattern})[df]?\\s*,\\s*(${numberPattern})[df]?\\s*\\]`)
    : new RegExp(`\\[\\s*(${numberPattern})[df]?\\s*,\\s*(${numberPattern})[df]?\\s*\\]`);
  const match = reply.match(vectorPattern);
  if (!match) throw new Error(`Could not parse player ${size === 3 ? 'position' : 'rotation'} from server response.`);
  return match.slice(1).map(Number);
}

if (!password || password.trim() !== password || !/^[A-Za-z0-9]+$/.test(password)) {
  console.error('RCON_PASSWORD must be a non-empty ASCII letters/digits value without surrounding whitespace.');
  process.exit(1);
}

let rcon;
try {
  const connectDeadline = Date.now() + 120_000;
  while (!rcon) {
    try {
      rcon = await Rcon.connect({
        host: '::1',
        port: 25575,
        password,
        connectTimeout: 8000,
        timeout: 20000,
        reconnect: false,
      });
    } catch (error) {
      if (error?.code !== 'CONNECTION_FAILED' || Date.now() >= connectDeadline) throw error;
      console.log('Waiting for the running Paper server to open RCON; retrying in 5 seconds.');
      await sleep(5000);
    }
  }
  console.log('RCON authenticated; the server will not be restarted by this action.');

  const playerPattern = new RegExp(`(?<![A-Za-z0-9_])${player}(?![A-Za-z0-9_])`, 'i');
  const playerDeadline = Date.now() + 120_000;
  let online = false;
  while (!online) {
    const reply = await rcon.send('list');
    online = playerPattern.test(reply);
    if (!online) {
      if (Date.now() >= playerDeadline) throw new Error(`${player} did not join; house build cancelled.`);
      console.log(`Waiting for ${player} to join; checking again in 5 seconds.`);
      await sleep(5000);
    }
  }

  const posReply = await rcon.send(`data get entity ${player} Pos`);
  const rotationReply = await rcon.send(`data get entity ${player} Rotation`);
  const [playerX, playerY, playerZ] = readVector(posReply, 3);
  const [yaw] = readVector(rotationReply, 2);

  // Snap the player's yaw to the nearest cardinal direction. House centre is 12 blocks ahead.
  const directionIndex = ((Math.round(yaw / 90) % 4) + 4) % 4;
  const directions = [
    { name: 'south', forwardX: 0, forwardZ: 1 },
    { name: 'west', forwardX: -1, forwardZ: 0 },
    { name: 'north', forwardX: 0, forwardZ: -1 },
    { name: 'east', forwardX: 1, forwardZ: 0 },
  ];
  const direction = directions[directionIndex];
  const rightX = -direction.forwardZ;
  const rightZ = direction.forwardX;
  const centerX = Math.floor(playerX + direction.forwardX * 12);
  const centerZ = Math.floor(playerZ + direction.forwardZ * 12);
  const floorY = Math.floor(playerY) - 1;

  const world = (u, v) => ({
    x: centerX + u * rightX + v * direction.forwardX,
    z: centerZ + u * rightZ + v * direction.forwardZ,
  });
  const fill = async (label, u1, v1, y1, u2, v2, y2, block, replaceAir = true) => {
    const points = [world(u1, v1), world(u1, v2), world(u2, v1), world(u2, v2)];
    const minX = Math.min(...points.map((point) => point.x));
    const maxX = Math.max(...points.map((point) => point.x));
    const minZ = Math.min(...points.map((point) => point.z));
    const maxZ = Math.max(...points.map((point) => point.z));
    const command = `fill ${minX} ${Math.min(y1, y2)} ${minZ} ${maxX} ${Math.max(y1, y2)} ${maxZ} ${block}${replaceAir ? ' replace minecraft:air' : ''}`;
    const reply = await rcon.send(command);
    if (/unknown or incomplete command|invalid block|invalid argument|expected .* at position|could not set block/i.test(reply)) {
      throw new Error(`${label} failed: ${reply}`);
    }
    console.log(`${label}: ${reply}`);
  };
  const set = async (label, u, v, y, block) => {
    const point = world(u, v);
    const reply = await rcon.send(`setblock ${point.x} ${y} ${point.z} ${block}`);
    if (/unknown or incomplete command|invalid block|invalid argument|expected .* at position|could not set block/i.test(reply)) {
      throw new Error(`${label} failed: ${reply}`);
    }
    console.log(`${label}: ${reply}`);
  };

  console.log(`Building a 7x7 spruce-and-stone cottage in front of ${player}, facing ${direction.name}.`);
  // Foundation only fills air below the floor; the floor deliberately replaces the surface inside the footprint.
  await fill('Stone-brick foundation', -3, -3, floorY - 1, 3, 3, floorY - 1, 'minecraft:stone_bricks');
  await fill('Spruce-plank floor', -3, -3, floorY, 3, 3, floorY, 'minecraft:spruce_planks', false);

  // Four exterior walls. Existing non-air blocks above the foundation are left untouched.
  await fill('Front wall', -3, -3, floorY + 1, 3, -3, floorY + 4, 'minecraft:white_concrete');
  await fill('Back wall', -3, 3, floorY + 1, 3, 3, floorY + 4, 'minecraft:white_concrete');
  await fill('Left wall', -3, -2, floorY + 1, -3, 2, floorY + 4, 'minecraft:white_concrete');
  await fill('Right wall', 3, -2, floorY + 1, 3, 2, floorY + 4, 'minecraft:white_concrete');

  for (const u of [-3, 3]) {
    for (const v of [-3, 3]) {
      await set('Spruce corner beam', u, v, floorY + 1, 'minecraft:stripped_spruce_log[axis=y]');
      const p = world(u, v);
      const reply = await rcon.send(`fill ${p.x} ${floorY + 2} ${p.z} ${p.x} ${floorY + 4} ${p.z} minecraft:stripped_spruce_log[axis=y] replace minecraft:air`);
      if (/unknown or incomplete command|invalid block|invalid argument|expected .* at position|could not set block/i.test(reply)) {
        throw new Error(`Spruce corner beam failed: ${reply}`);
      }
    }
  }

  // Gable ends above the walls.
  await fill('Front gable', -2, -3, floorY + 5, 2, -3, floorY + 5, 'minecraft:white_concrete');
  await fill('Front gable upper', -1, -3, floorY + 6, 1, -3, floorY + 6, 'minecraft:white_concrete');
  await fill('Front gable peak', 0, -3, floorY + 7, 0, -3, floorY + 7, 'minecraft:white_concrete');
  await fill('Back gable', -2, 3, floorY + 5, 2, 3, floorY + 5, 'minecraft:white_concrete');
  await fill('Back gable upper', -1, 3, floorY + 6, 1, 3, floorY + 6, 'minecraft:white_concrete');
  await fill('Back gable peak', 0, 3, floorY + 7, 0, 3, floorY + 7, 'minecraft:white_concrete');

  // Glazed windows, two blocks high.
  for (const u of [-2, 2]) {
    await set('Front window pane', u, -3, floorY + 2, 'minecraft:glass_pane');
    await set('Front window pane', u, -3, floorY + 3, 'minecraft:glass_pane');
    await set('Back window pane', u, 3, floorY + 2, 'minecraft:glass_pane');
    await set('Back window pane', u, 3, floorY + 3, 'minecraft:glass_pane');
  }
  for (const u of [-3, 3]) {
    await set('Side window pane', u, 0, floorY + 2, 'minecraft:glass_pane');
    await set('Side window pane', u, 0, floorY + 3, 'minecraft:glass_pane');
  }

  // A front door that faces back toward the player.
  await set('Spruce door lower half', 0, -3, floorY + 1, `minecraft:spruce_door[facing=${direction.name},half=lower,hinge=left,open=false,powered=false]`);
  await set('Spruce door upper half', 0, -3, floorY + 2, `minecraft:spruce_door[facing=${direction.name},half=upper,hinge=left,open=false,powered=false]`);

  // Stepped dark-oak gable roof, with a small brick chimney.
  for (let u = -4; u <= 4; u += 1) {
    const roofY = floorY + 5 + Math.max(0, 3 - Math.abs(u));
    await fill('Dark-oak roof course', u, -4, roofY, u, 4, roofY, 'minecraft:dark_oak_planks');
  }
  const chimneyHeight = floorY + 9;
  const chimneyPoint = world(2, 2);
  const chimneyReply = await rcon.send(`fill ${chimneyPoint.x} ${floorY + 7} ${chimneyPoint.z} ${chimneyPoint.x} ${chimneyHeight} ${chimneyPoint.z} minecraft:bricks replace minecraft:air`);
  if (/unknown or incomplete command|invalid block|invalid argument|expected .* at position|could not set block/i.test(chimneyReply)) {
    throw new Error(`Brick chimney failed: ${chimneyReply}`);
  }
  console.log(`Brick chimney: ${chimneyReply}`);

  const lamp = world(2, 2);
  const lampReply = await rcon.send(`setblock ${lamp.x} ${floorY + 1} ${lamp.z} minecraft:lantern`);
  if (/unknown or incomplete command|invalid block|invalid argument|expected .* at position|could not set block/i.test(lampReply)) {
    throw new Error(`Interior lantern failed: ${lampReply}`);
  }

  await fill('Stone-brick entry path', 0, -10, floorY, 0, -4, floorY, 'minecraft:stone_bricks', false);
  console.log(`Cottage complete in front of ${player}.`);
} catch (error) {
  console.error(`Cottage build failed (${error?.code ?? error?.name ?? 'unknown'}): ${error?.message ?? 'no details'}`);
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
