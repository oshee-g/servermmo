import { Rcon } from 'rcon-client';

const player = 'OSheepka';
const password = process.env.RCON_PASSWORD ?? '';
const wait = (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds));

if (!password || password.trim() !== password) {
  console.error('RCON_PASSWORD is missing or has leading/trailing whitespace.');
  process.exit(1);
}
if (!/^[A-Za-z0-9]+$/.test(password)) {
  console.error('RCON_PASSWORD must contain only ASCII letters and digits for this test.');
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
      if (error?.code !== 'CONNECTION_FAILED' || Date.now() >= connectDeadline) {
        throw error;
      }
      console.log('Local RCON is not listening yet; retrying in 5 seconds without restarting the server.');
      await wait(5000);
    }
  }
  console.log('RCON authentication succeeded.');

  const playerPattern = new RegExp(`(?<![A-Za-z0-9_])${player}(?![A-Za-z0-9_])`, 'i');
  const playerDeadline = Date.now() + 120_000;
  let isOnline = false;
  while (!isOnline) {
    const playerList = await rcon.send('list');
    isOnline = playerPattern.test(playerList);
    if (!isOnline) {
      if (Date.now() >= playerDeadline) {
        throw new Error(`Player ${player} did not come online; no reward commands were sent.`);
      }
      console.log(`Waiting for ${player} to join; checking again in 5 seconds.`);
      await wait(5000);
    }
  }
  console.log(`Confirmed ${player} is online.`);

  const giveReply = await rcon.send(`give ${player} minecraft:diamond 10`);
  console.log(`Give 10 diamonds: ${giveReply}`);

  const platformReply = await rcon.send(
    `execute at ${player} run fill ~-1 ~-1 ~-1 ~1 ~-1 ~1 minecraft:oak_planks`,
  );
  console.log(`Place 3x3 oak-plank platform below player: ${platformReply}`);
  console.log('RCON test reward completed.');
} catch (error) {
  if (error?.code === 'AUTH_FAILED') {
    console.error('RCON authentication failed; no game commands were sent.');
  } else {
    console.error(`RCON test failed (${error?.code ?? error?.name ?? 'unknown'}): ${error?.message ?? 'no details'}`);
  }
  process.exitCode = 1;
} finally {
  if (rcon) {
    try {
      await rcon.end();
    } catch {
      // Preserve the original command result; closing a failed RCON socket is best effort.
    }
  }
}
