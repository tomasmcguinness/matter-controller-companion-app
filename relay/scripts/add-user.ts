// Adds or replaces a user in the relay's users file.
//
//   npm run add-user -- <username>
//
// Prompts for the password so it doesn't end up in shell history.

import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { createInterface } from 'node:readline';
import { hashPassword, type Users } from '../src/users.js';

const username = process.argv[2];
const path = process.env.USERS_FILE ?? 'users.json';

if (!username || username.includes(':')) {
  console.error('Usage: npm run add-user -- <username>   (usernames cannot contain ":")');
  process.exit(1);
}

function promptHidden(question: string): Promise<string> {
  const rl = createInterface({ input: process.stdin, output: process.stdout, terminal: true });
  let prompted = false;

  // Swallow the echo of what's typed, but still print the prompt itself.
  (rl as unknown as { _writeToOutput: (text: string) => void })._writeToOutput = (text) => {
    if (!prompted) {
      process.stdout.write(text);
      prompted = true;
    }
  };

  return new Promise((resolve) => {
    rl.question(question, (answer) => {
      rl.close();
      process.stdout.write('\n');
      resolve(answer);
    });
  });
}

const password = await promptHidden(`Password for ${username}: `);

if (!password) {
  console.error('Password cannot be empty.');
  process.exit(1);
}

const users: Users = existsSync(path) ? JSON.parse(readFileSync(path, 'utf8')) : {};

users[username] = await hashPassword(password);

writeFileSync(path, JSON.stringify(users, null, 2) + '\n', { mode: 0o600 });

console.log(`Saved ${username} to ${path}.`);
