import { sign, type KeyObject } from 'node:crypto';
import type { Alert, SendResult } from './apns.js';

export interface FcmSender {
  send(deviceToken: string, alert: Alert): Promise<SendResult>;
}

export interface FcmConfig {
  projectId: string;
  /** The service account's `client_email`. */
  clientEmail: string;
  /** The service account's `private_key`. */
  key: KeyObject;
  /** The service account's `token_uri`. */
  tokenUri: string;
}

/** The notification channel the Android app creates; see `MccApplication.kt`. */
const ANDROID_CHANNEL_ID = 'controllers';

const SCOPE = 'https://www.googleapis.com/auth/firebase.messaging';

const REQUEST_TIMEOUT_MS = 10_000;

// Google access tokens last an hour; fetch a new one a few minutes before that.
const TOKEN_REFRESH_MARGIN_SECONDS = 5 * 60;

/** Sends alerts to Firebase Cloud Messaging with the HTTP v1 API and a service account. */
export class FcmClient implements FcmSender {

  private accessToken?: { value: string; expiresAt: number };

  constructor(private readonly config: FcmConfig, private readonly fetchImpl: typeof fetch = fetch) {}

  async send(deviceToken: string, alert: Alert): Promise<SendResult> {
    const message = {
      token: deviceToken,
      notification: alert,
      android: { priority: 'high', notification: { channel_id: ANDROID_CHANNEL_ID } },
    };

    const response = await this.fetchImpl(
      `https://fcm.googleapis.com/v1/projects/${encodeURIComponent(this.config.projectId)}/messages:send`,
      {
        method: 'POST',
        headers: {
          authorization: `Bearer ${await this.authToken()}`,
          'content-type': 'application/json',
        },
        body: JSON.stringify({ message }),
        signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
      });

    if (response.ok) {
      return { ok: true };
    }

    let reason = 'Unknown';

    try {
      const { error } = await response.json() as FcmErrorBody;
      // The FCM-specific code (e.g. UNREGISTERED) is more useful than the generic status.
      reason = error?.details?.find((detail) => detail.errorCode)?.errorCode ?? error?.status ?? reason;
    } catch {
      // FCM sends a JSON body with errors; if it didn't, the status is all we have.
    }

    return { ok: false, status: response.status, reason };
  }

  private async authToken(): Promise<string> {
    const now = Math.floor(Date.now() / 1000);

    if (this.accessToken && now < this.accessToken.expiresAt) {
      return this.accessToken.value;
    }

    const header = Buffer.from(JSON.stringify({ alg: 'RS256', typ: 'JWT' })).toString('base64url');
    const claims = Buffer.from(JSON.stringify({
      iss: this.config.clientEmail,
      scope: SCOPE,
      aud: this.config.tokenUri,
      iat: now,
      exp: now + 3600,
    })).toString('base64url');
    const input = `${header}.${claims}`;
    const signature = sign('sha256', Buffer.from(input), this.config.key).toString('base64url');

    const response = await this.fetchImpl(this.config.tokenUri, {
      method: 'POST',
      headers: { 'content-type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({
        grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer',
        assertion: `${input}.${signature}`,
      }),
      signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
    });

    if (!response.ok) {
      throw new Error(`Google token exchange failed with ${response.status}: ${await response.text()}`);
    }

    const { access_token, expires_in } = await response.json() as { access_token: string; expires_in: number };

    this.accessToken = { value: access_token, expiresAt: now + expires_in - TOKEN_REFRESH_MARGIN_SECONDS };

    return access_token;
  }
}

interface FcmErrorBody {
  error?: {
    status?: string;
    details?: { errorCode?: string }[];
  };
}
