/**
 * Responses from a RoomEngine command.
 * TypeScript port of KMP's RoomEngineResponse.kt.
 */

import type { RoomSnapshot } from '../../protocol/models';

export type RoomEngineResponse = OkResponse | RejectedResponse;

export interface OkResponse {
  type: 'ok';
  snapshot: RoomSnapshot;
}

export interface RejectedResponse {
  type: 'rejected';
  code: string;
  reason: string;
}
