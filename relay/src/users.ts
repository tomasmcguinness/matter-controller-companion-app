import { readFile } from 'node:fs/promises';
import { randomBytes, scrypt, timingSafeEqual } from 'node:crypto';
import { promisify } from 'node:util';

const scryptAsync = promisify(scrypt) as (password: string, salt: Buffer, keylen: number) => Promise<Buffer>;

const KEY_LENGTH = 64;

/** Username to `<salt>:<hash>`, both base64. */
export type Users = Record<string, string>;

export type UserLoader = () => Promise<Users>;

/**
 * Reads the users file on every call, so `npm run add-user` takes effect without a restart.
 * The file is a handful of lines, so this costs nothing worth caching.
 */
export function fileUserLoader(path: string): UserLoader {
  return async () => JSON.parse(await readFile(path, 'utf8')) as Users;
}

export async function hashPassword(password: string): Promise<string> {
  const salt = randomBytes(16);
  const hash = await scryptAsync(password, salt, KEY_LENGTH);

  return `${salt.toString('base64')}:${hash.toString('base64')}`;
}

async function verifyPassword(password: string, stored: string): Promise<boolean> {
  const [salt, hash] = stored.split(':');

  if (!salt || !hash) {
    return false;
  }

  const expected = Buffer.from(hash, 'base64');
  const actual = await scryptAsync(password, Buffer.from(salt, 'base64'), expected.length);

  return actual.length === expected.length && timingSafeEqual(actual, expected);
}

// Hashed against when the username doesn't exist, so an unknown user takes as long to reject
// as a wrong password and the response time doesn't reveal which usernames are real.
const decoy = hashPassword(randomBytes(16).toString('hex'));

export async function checkCredentials(users: Users, username: string, password: string): Promise<boolean> {
  const stored = Object.hasOwn(users, username) ? users[username] : undefined;

  if (stored === undefined) {
    await verifyPassword(password, await decoy);
    return false;
  }

  return verifyPassword(password, stored);
}
