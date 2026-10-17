import { connect, constants, type ClientHttp2Session } from 'node:http2';
import { sign, type KeyObject } from 'node:crypto';

export type Environment = 'sandbox' | 'production';

export interface Alert {
  title: string;
  body: string;
}

export type SendResult = { ok: true } | { ok: false; status: number; reason: string };

export interface PushSender {
  send(deviceToken: string, environment: Environment, alert: Alert): Promise<SendResult>;
}

export interface ApnsConfig {
  /** The .p8 signing key. */
  key: KeyObject;
  keyId: string;
  teamId: string;
  /** The app's bundle id. */
  topic: string;
}

const hosts: Record<Environment, string> = {
  sandbox: 'https://api.sandbox.push.apple.com',
  production: 'https://api.push.apple.com',
};

// APNs rejects tokens older than an hour and throttles ones refreshed more often than every
// 20 minutes, so reuse one for most of that window.
const TOKEN_LIFETIME_SECONDS = 50 * 60;

const REQUEST_TIMEOUT_MS = 10_000;

/** Sends alerts to APNs using token-based (.p8) authentication over HTTP/2. */
export class ApnsClient implements PushSender {

  private token?: { value: string; issuedAt: number };
  private readonly sessions = new Map<Environment, ClientHttp2Session>();

  constructor(private readonly config: ApnsConfig) {}

  send(deviceToken: string, environment: Environment, alert: Alert): Promise<SendResult> {
    const payload = JSON.stringify({ aps: { alert, sound: 'default' } });

    return new Promise((resolve, reject) => {
      const request = this.session(environment).request({
        ':method': 'POST',
        ':path': `/3/device/${deviceToken}`,
        authorization: `bearer ${this.authToken()}`,
        'apns-topic': this.config.topic,
        'apns-push-type': 'alert',
        'content-type': 'application/json',
      });

      let status = 0;
      const chunks: Buffer[] = [];

      request.setTimeout(REQUEST_TIMEOUT_MS, () => {
        request.close(constants.NGHTTP2_CANCEL);
        reject(new Error('APNs request timed out'));
      });

      request.on('response', (headers) => {
        status = Number(headers[':status']);
      });
      request.on('data', (chunk: Buffer) => chunks.push(chunk));
      request.on('error', reject);
      request.on('end', () => {
        if (status === 200) {
          resolve({ ok: true });
          return;
        }

        let reason = 'Unknown';

        try {
          reason = JSON.parse(Buffer.concat(chunks).toString('utf8')).reason ?? reason;
        } catch {
          // APNs always sends a JSON body with errors; if it didn't, the status is all we have.
        }

        resolve({ ok: false, status, reason });
      });

      request.end(payload);
    });
  }

  private authToken(): string {
    const now = Math.floor(Date.now() / 1000);

    if (this.token && now - this.token.issuedAt < TOKEN_LIFETIME_SECONDS) {
      return this.token.value;
    }

    const header = Buffer.from(JSON.stringify({ alg: 'ES256', kid: this.config.keyId })).toString('base64url');
    const claims = Buffer.from(JSON.stringify({ iss: this.config.teamId, iat: now })).toString('base64url');
    const input = `${header}.${claims}`;

    // JWS wants the raw r||s signature, not the DER encoding Node produces by default.
    const signature = sign('sha256', Buffer.from(input), { key: this.config.key, dsaEncoding: 'ieee-p1363' });

    this.token = { value: `${input}.${signature.toString('base64url')}`, issuedAt: now };

    return this.token.value;
  }

  /** One long-lived connection per environment, as APNs asks; reopened if it drops. */
  private session(environment: Environment): ClientHttp2Session {
    const existing = this.sessions.get(environment);

    if (existing && !existing.closed && !existing.destroyed) {
      return existing;
    }

    const session = connect(hosts[environment]);
    const forget = () => {
      if (this.sessions.get(environment) === session) {
        this.sessions.delete(environment);
      }
    };

    session.on('error', forget);
    session.on('goaway', forget);
    session.on('close', forget);

    this.sessions.set(environment, session);

    return session;
  }
}
