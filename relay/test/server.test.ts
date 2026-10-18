import { beforeAll, describe, expect, it } from 'vitest';
import type { Alert, Environment, PushSender, SendResult } from '../src/apns.js';
import type { FcmSender } from '../src/fcm.js';
import { buildServer } from '../src/server.js';
import { hashPassword, type Users } from '../src/users.js';

class StubSender implements PushSender {
  sent: { deviceToken: string; environment: Environment; alert: Alert }[] = [];
  result: SendResult | Error = { ok: true };

  async send(deviceToken: string, environment: Environment, alert: Alert): Promise<SendResult> {
    this.sent.push({ deviceToken, environment, alert });

    if (this.result instanceof Error) {
      throw this.result;
    }

    return this.result;
  }
}

class StubFcmSender implements FcmSender {
  sent: { deviceToken: string; alert: Alert }[] = [];
  result: SendResult = { ok: true };

  async send(deviceToken: string, alert: Alert): Promise<SendResult> {
    this.sent.push({ deviceToken, alert });
    return this.result;
  }
}

const token = 'a1b2c3d4e5f6'.repeat(6);

const fcmToken = 'dGhpcyBpcyBub3Q:APA91bH-real_token' + 'x'.repeat(100);

const validBody = { deviceToken: token, environment: 'sandbox', title: 'Heating Monitor', body: 'Too hot' };

function basic(username: string, password: string) {
  return `Basic ${Buffer.from(`${username}:${password}`).toString('base64')}`;
}

let users: Users;

beforeAll(async () => {
  users = { tomas: await hashPassword('correct horse') };
});

function setup({ withApns = true, withFcm = true } = {}) {
  const sender = new StubSender();
  const fcmSender = new StubFcmSender();
  const app = buildServer({
    loadUsers: async () => users,
    senders: { apns: withApns ? sender : undefined, fcm: withFcm ? fcmSender : undefined },
  });

  const push = (payload: unknown, authorization?: string) =>
    app.inject({
      method: 'POST',
      url: '/v1/push',
      headers: authorization ? { authorization } : {},
      payload: payload as object,
    });

  return { sender, fcmSender, push };
}

describe('POST /v1/push', () => {
  it('forwards a valid push to APNs', async () => {
    const { sender, push } = setup();

    const response = await push(validBody, basic('tomas', 'correct horse'));

    expect(response.statusCode).toBe(202);
    expect(sender.sent).toEqual([
      { deviceToken: token, environment: 'sandbox', alert: { title: 'Heating Monitor', body: 'Too hot' } },
    ]);
  });

  it.each([
    ['no credentials', undefined],
    ['a wrong password', basic('tomas', 'wrong')],
    ['an unknown user', basic('nobody', 'correct horse')],
    ['a malformed header', 'Basic not-base64-colon'],
    ['a non-basic scheme', 'Bearer abc'],
  ])('rejects %s with 401', async (_, authorization) => {
    const { sender, push } = setup();

    const response = await push(validBody, authorization);

    expect(response.statusCode).toBe(401);
    expect(response.headers['www-authenticate']).toContain('Basic');
    expect(sender.sent).toHaveLength(0);
  });

  it('checks credentials before validating the body', async () => {
    const { push } = setup();

    const response = await push({}, basic('tomas', 'wrong'));

    expect(response.statusCode).toBe(401);
  });

  it.each([
    ['a non-hex token', { ...validBody, deviceToken: '../../etc' }],
    ['a missing token', { ...validBody, deviceToken: undefined }],
    ['an unknown environment', { ...validBody, environment: 'staging' }],
    ['a missing title', { ...validBody, title: undefined }],
    ['a non-string body', { ...validBody, body: 42 }],
  ])('rejects %s with 400', async (_, payload) => {
    const { sender, push } = setup();

    const response = await push(payload, basic('tomas', 'correct horse'));

    expect(response.statusCode).toBe(400);
    expect(response.json().error).toBeTypeOf('string');
    expect(sender.sent).toHaveLength(0);
  });

  it('accepts long titles and bodies', async () => {
    const { push } = setup();

    const response = await push({ ...validBody, title: 'x'.repeat(500), body: 'y'.repeat(5000) },
                                basic('tomas', 'correct horse'));

    expect(response.statusCode).toBe(202);
  });

  it.each([
    [{ ok: false, status: 410, reason: 'Unregistered' } as const],
    [{ ok: false, status: 400, reason: 'BadDeviceToken' } as const],
  ])('maps a dead token to 410', async (result) => {
    const { sender, push } = setup();
    sender.result = result;

    const response = await push(validBody, basic('tomas', 'correct horse'));

    expect(response.statusCode).toBe(410);
    expect(response.json().error).toBe(result.reason);
  });

  it('maps other APNs rejections to 502 with the reason', async () => {
    const { sender, push } = setup();
    sender.result = { ok: false, status: 403, reason: 'InvalidProviderToken' };

    const response = await push(validBody, basic('tomas', 'correct horse'));

    expect(response.statusCode).toBe(502);
    expect(response.json().error).toBe('InvalidProviderToken');
  });

  it('sends an fcm push to FCM, not APNs', async () => {
    const { sender, fcmSender, push } = setup();

    const response = await push({ ...validBody, deviceToken: fcmToken, environment: 'fcm' },
                                basic('tomas', 'correct horse'));

    expect(response.statusCode).toBe(202);
    expect(sender.sent).toHaveLength(0);
    expect(fcmSender.sent).toEqual([{ deviceToken: fcmToken, alert: { title: 'Heating Monitor', body: 'Too hot' } }]);
  });

  it('rejects an fcm token with characters FCM never uses', async () => {
    const { fcmSender, push } = setup();

    const response = await push({ ...validBody, deviceToken: 'abc/../def', environment: 'fcm' },
                                basic('tomas', 'correct horse'));

    expect(response.statusCode).toBe(400);
    expect(fcmSender.sent).toHaveLength(0);
  });

  it('still requires a hex token for APNs', async () => {
    const { push } = setup();

    const response = await push({ ...validBody, deviceToken: fcmToken }, basic('tomas', 'correct horse'));

    expect(response.statusCode).toBe(400);
  });

  it.each([
    [{ ok: false, status: 404, reason: 'UNREGISTERED' } as const],
    [{ ok: false, status: 400, reason: 'INVALID_ARGUMENT' } as const],
  ])('maps a dead FCM token to 410', async (result) => {
    const { fcmSender, push } = setup();
    fcmSender.result = result;

    const response = await push({ ...validBody, deviceToken: fcmToken, environment: 'fcm' },
                                basic('tomas', 'correct horse'));

    expect(response.statusCode).toBe(410);
    expect(response.json().error).toBe(result.reason);
  });

  it.each([
    ['Android', { withFcm: false }, { ...validBody, deviceToken: fcmToken, environment: 'fcm' }],
    ['iOS', { withApns: false }, validBody],
  ])('says so when the relay is not set up for %s', async (platform, options, payload) => {
    const { push } = setup(options);

    const response = await push(payload, basic('tomas', 'correct horse'));

    expect(response.statusCode).toBe(400);
    expect(response.json().error).toContain(platform);
  });

  it('maps a connection failure to 502', async () => {
    const { sender, push } = setup();
    sender.result = new Error('ECONNREFUSED');

    const response = await push(validBody, basic('tomas', 'correct horse'));

    expect(response.statusCode).toBe(502);
  });
});
