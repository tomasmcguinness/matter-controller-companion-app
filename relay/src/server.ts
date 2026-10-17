import Fastify, { type FastifyServerOptions } from 'fastify';
import type { Environment, PushSender } from './apns.js';
import { checkCredentials, type UserLoader } from './users.js';

export interface ServerOptions {
  loadUsers: UserLoader;
  sender: PushSender;
  logger?: FastifyServerOptions['logger'];
}

interface PushBody {
  deviceToken: string;
  environment: Environment;
  title: string;
  body: string;
}

// APNs reasons that mean the token will never work again, so the controller should forget it.
const deadTokenReasons = new Set(['Unregistered', 'BadDeviceToken', 'ExpiredToken']);

export function buildServer({ loadUsers, sender, logger = false }: ServerOptions) {
  const app = Fastify({ logger });

  app.get('/healthz', async () => ({ ok: true }));

  app.post('/v1/push', async (request, reply) => {
    const credentials = parseBasicAuth(request.headers.authorization);

    if (!credentials || !(await checkCredentials(await loadUsers(), credentials.username, credentials.password))) {
      return reply
        .code(401)
        .header('WWW-Authenticate', 'Basic realm="MCC Push Relay"')
        .send({ error: 'Invalid username or password.' });
    }

    const problem = validate(request.body);

    if (problem) {
      return reply.code(400).send({ error: problem });
    }

    const { deviceToken, environment, title, body } = request.body as PushBody;

    let result;

    try {
      result = await sender.send(deviceToken, environment, { title, body });
    } catch (error) {
      request.log.error({ err: error }, 'APNs request failed');
      return reply.code(502).send({ error: "Couldn't reach APNs." });
    }

    if (result.ok) {
      return reply.code(202).send({});
    }

    request.log.warn({ status: result.status, reason: result.reason, token: redact(deviceToken) }, 'APNs rejected push');

    if (result.status === 410 || deadTokenReasons.has(result.reason)) {
      return reply.code(410).send({ error: result.reason });
    }

    return reply.code(502).send({ error: result.reason });
  });

  return app;
}

function parseBasicAuth(header: string | undefined): { username: string; password: string } | undefined {
  if (!header?.startsWith('Basic ')) {
    return undefined;
  }

  const decoded = Buffer.from(header.slice(6), 'base64').toString('utf8');
  const separator = decoded.indexOf(':');

  if (separator < 0) {
    return undefined;
  }

  return { username: decoded.slice(0, separator), password: decoded.slice(separator + 1) };
}

function validate(body: unknown): string | undefined {
  if (typeof body !== 'object' || body === null) {
    return 'Send a JSON body with deviceToken, environment, title and body.';
  }

  const { deviceToken, environment, title, body: text } = body as Record<string, unknown>;

  // The token goes into the APNs request path, so only accept what a real one looks like.
  if (typeof deviceToken !== 'string' || !/^[0-9a-fA-F]+$/.test(deviceToken)) {
    return 'deviceToken must be a hex string.';
  }

  if (environment !== 'sandbox' && environment !== 'production') {
    return 'environment must be "sandbox" or "production".';
  }

  if (typeof title !== 'string') {
    return 'title must be a string.';
  }

  if (typeof text !== 'string') {
    return 'body must be a string.';
  }

  return undefined;
}

function redact(token: string): string {
  return `${token.slice(0, 6)}…`;
}
