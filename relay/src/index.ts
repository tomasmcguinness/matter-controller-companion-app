import { existsSync, readFileSync } from 'node:fs';
import { createPrivateKey } from 'node:crypto';
import { ApnsClient } from './apns.js';
import { buildServer } from './server.js';
import { fileUserLoader } from './users.js';

// Handy for local runs; in a container the variables come from the environment instead.
if (existsSync('.env')) {
  process.loadEnvFile('.env');
}

function required(name: string): string {
  const value = process.env[name];

  if (!value) {
    console.error(`Missing required environment variable ${name}. See .env.example.`);
    process.exit(1);
  }

  return value;
}

const sender = new ApnsClient({
  key: createPrivateKey(readFileSync(required('APNS_KEY_PATH'))),
  keyId: required('APNS_KEY_ID'),
  teamId: required('APNS_TEAM_ID'),
  topic: required('APNS_TOPIC'),
});

const app = buildServer({
  loadUsers: fileUserLoader(process.env.USERS_FILE ?? 'users.json'),
  sender,
  logger: true,
});

await app.listen({ host: '0.0.0.0', port: Number(process.env.PORT ?? 8080) });
