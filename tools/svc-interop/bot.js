// Minecraft connection for the Simple Voice Chat interoperability probe.
//
//   node bot.js <host> <port> <minecraft-version> -- <probe command...>
//
// Joins an offline-mode server as a plain client (minecraft-protocol), spawns
// the MCVoice probe (dev.mcvoice.client.svc.tools.SvcProbe) and relays plugin
// messages between the server and the probe using the line protocol that
// SvcProbe documents. The bot has no Simple Voice Chat logic of its own: every
// SVC byte is produced and consumed by MCVoice's svc-compat code.
'use strict';
const { spawn } = require('child_process');
const readline = require('readline');
const mc = require('minecraft-protocol');

const [host, port, version, sep, ...probe] = process.argv.slice(2);
if (sep !== '--' || probe.length === 0) {
  console.error('usage: node bot.js <host> <port> <version> -- <probe command...>');
  process.exit(2);
}

const child = spawn(probe[0], probe.slice(1), { stdio: ['pipe', 'pipe', 'inherit'] });
const toProbe = (line) => child.stdin.write(line + '\n');
let registerChannels = [];
let client = null;
let joined = false;
const serverChannels = new Set();

function sendPayload(channel, data) {
  if (client) client.write('custom_payload', { channel, data });
}

function registerOurs() {
  if (registerChannels.length) sendPayload('minecraft:register', Buffer.from(registerChannels.join('\0'), 'utf8'));
}

readline.createInterface({ input: child.stdout }).on('line', (line) => {
  if (line.startsWith('REGISTER ')) {
    registerChannels = line.slice(9).split(',').filter(Boolean);
    if (joined) registerOurs();
  } else if (line.startsWith('SEND ')) {
    const [, channel, hex] = line.split(' ');
    sendPayload(channel, Buffer.from(hex || '', 'hex'));
    console.error(`bot: -> ${channel} (${(hex || '').length / 2} bytes)`);
  } else if (line.startsWith('RESULT ')) {
    console.log(line.slice(7));
  } else {
    console.error('probe: ' + line);
  }
});
child.on('exit', (code) => {
  console.error(`bot: probe exited with ${code}`);
  if (client) client.end('probe finished');
  process.exit(code === null ? 1 : code);
});

client = mc.createClient({ host, port: Number(port), username: 'mcvoice_probe', auth: 'offline', version });
client.on('custom_payload', (packet) => {
  const channel = packet.channel;
  const data = packet.data || Buffer.alloc(0);
  if (channel === 'minecraft:register' || channel === 'REGISTER') {
    for (const c of data.toString('utf8').split('\0')) if (c) serverChannels.add(c);
    toProbe('CHANNELS ' + [...serverChannels].join(','));
    console.error('bot: server registered ' + [...serverChannels].join(','));
    return;
  }
  if (channel.startsWith('voicechat:')) {
    console.error(`bot: <- ${channel} (${data.length} bytes)`);
    toProbe(`RECV ${channel} ${data.toString('hex')}`);
  }
});
client.on('login', () => {
  joined = true;
  console.error('bot: joined (play state)');
  registerOurs();
  toProbe('JOINED');
});
client.on('kick_disconnect', (p) => console.error('bot: kicked: ' + JSON.stringify(p)));
client.on('disconnect', (p) => console.error('bot: disconnected: ' + JSON.stringify(p)));
client.on('error', (e) => console.error('bot: error: ' + e));
client.on('end', (reason) => {
  console.error('bot: connection ended: ' + reason);
  if (!child.killed) child.kill();
});
