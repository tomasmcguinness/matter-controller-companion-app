import { generateKeyPairSync } from 'node:crypto';
import { describe, expect, it } from 'vitest';
import { FcmClient } from '../src/fcm.js';

const { privateKey } = generateKeyPairSync('rsa', { modulusLength: 2048 });

const config = {
  projectId: 'mcc-test',
  clientEmail: 'relay@mcc-test.iam.gserviceaccount.com',
  key: privateKey,
  tokenUri: 'https://oauth2.googleapis.com/token',
};

/** Answers the token exchange, then each send with the next of `sendResponses`. */
function fakeFetch(...sendResponses: Response[]) {
  const calls: { url: string; init?: RequestInit }[] = [];

  const fetchImpl = (async (url: string, init?: RequestInit) => {
    calls.push({ url, init });

    if (url === config.tokenUri) {
      return Response.json({ access_token: 'access-123', expires_in: 3600 });
    }

    return sendResponses.shift() ?? Response.json({ name: 'projects/mcc-test/messages/1' });
  }) as typeof fetch;

  return { fetchImpl, calls };
}

describe('FcmClient', () => {
  it('sends the alert to the token with a fresh access token', async () => {
    const { fetchImpl, calls } = fakeFetch();
    const client = new FcmClient(config, fetchImpl);

    const result = await client.send('token-1', { title: 'Heating Monitor', body: 'Too hot' });

    expect(result).toEqual({ ok: true });
    expect(calls.map((call) => call.url)).toEqual([
      config.tokenUri,
      'https://fcm.googleapis.com/v1/projects/mcc-test/messages:send',
    ]);

    const send = calls[1].init!;
    expect((send.headers as Record<string, string>).authorization).toBe('Bearer access-123');
    expect(JSON.parse(send.body as string).message).toMatchObject({
      token: 'token-1',
      notification: { title: 'Heating Monitor', body: 'Too hot' },
    });
  });

  it('reuses the access token', async () => {
    const { fetchImpl, calls } = fakeFetch();
    const client = new FcmClient(config, fetchImpl);

    await client.send('token-1', { title: 'a', body: 'b' });
    await client.send('token-1', { title: 'a', body: 'b' });

    expect(calls.filter((call) => call.url === config.tokenUri)).toHaveLength(1);
  });

  it('reports the FCM error code', async () => {
    const { fetchImpl } = fakeFetch(Response.json({
      error: {
        code: 404,
        status: 'NOT_FOUND',
        details: [{ '@type': 'type.googleapis.com/google.firebase.fcm.v1.FcmError', errorCode: 'UNREGISTERED' }],
      },
    }, { status: 404 }));
    const client = new FcmClient(config, fetchImpl);

    expect(await client.send('token-1', { title: 'a', body: 'b' }))
      .toEqual({ ok: false, status: 404, reason: 'UNREGISTERED' });
  });

  it('falls back to the status when there is no error code', async () => {
    const { fetchImpl } = fakeFetch(Response.json({ error: { status: 'UNAVAILABLE' } }, { status: 503 }));
    const client = new FcmClient(config, fetchImpl);

    expect(await client.send('token-1', { title: 'a', body: 'b' }))
      .toEqual({ ok: false, status: 503, reason: 'UNAVAILABLE' });
  });
});
