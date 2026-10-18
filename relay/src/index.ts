import { existsSync, readFileSync } from 'node:fs';
import { createPrivateKey } from 'node:crypto';
import { ApnsClient } from './apns.js';
import { FcmClient } from './fcm.js';
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

// Each platform is optional, so a relay can serve only iPhones or only Android phones.
const apns = process.env.APNS_KEY_PATH
  ? new ApnsClient({
    key: createPrivateKey(readFileSync(process.env.APNS_KEY_PATH)),
    keyId: required('APNS_KEY_ID'),
    teamId: required('APNS_TEAM_ID'),
    topic: required('APNS_TOPIC'),
  })
  : undefined;

const fcm = process.env.FCM_SERVICE_ACCOUNT_PATH
  ? (() => {
    const account = JSON.parse(readFileSync(process.env.FCM_SERVICE_ACCOUNT_PATH, 'utf8'));

    return new FcmClient({
      projectId: account.project_id,
      clientEmail: account.client_email,
      key: createPrivateKey(account.private_key),
      tokenUri: account.token_uri ?? 'https://oauth2.googleapis.com/token',
    });
  })()
  : undefined;

if (!apns && !fcm) {
  console.error('Set APNS_KEY_PATH, FCM_SERVICE_ACCOUNT_PATH, or both. See .env.example.');
  process.exit(1);
}

const app = buildServer({
  loadUsers: fileUserLoader(process.env.USERS_FILE ?? 'users.json'),
  senders: { apns, fcm },
  logger: true,
});

await app.listen({ host: '0.0.0.0', port: Number(process.env.PORT ?? 8080) });
