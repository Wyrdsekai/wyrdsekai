/**
 * Lightweight cancellation signal that propagates through the actor chain (RN port).
 *
 * Parent cancellation propagates to all children.
 * Used by InferenceRouter (skip cancelled queue entries, discard stale responses)
 * and CompanionEngine (cancel on new input, abort command).
 */

export class CancellationToken {
  private _cancelled = false;
  private _reason: string | null = null;
  readonly createdAt: number = Date.now();
  private readonly children: CancellationToken[] = [];

  /** Check if cancellation has been requested. */
  get isCancelled(): boolean {
    return this._cancelled;
  }

  /** Get the cancellation reason (null if not cancelled). */
  get reason(): string | null {
    return this._reason;
  }

  /**
   * Request cancellation. Propagates to all child tokens.
   *
   * @param reason human-readable reason for cancellation
   */
  cancel(reason: string): void {
    if (this._cancelled) return;
    this._cancelled = true;
    this._reason = reason;
    for (const child of this.children) {
      child.cancel(`parent: ${reason}`);
    }
  }

  /**
   * Create a child token that is cancelled when this parent is cancelled.
   * If the parent is already cancelled, the child starts cancelled.
   */
  child(): CancellationToken {
    const child = new CancellationToken();
    if (this._cancelled) {
      child.cancel(`parent already cancelled: ${this._reason}`);
    } else {
      this.children.push(child);
    }
    return child;
  }

  /**
   * A shared token that is never cancelled.
   * For code paths that don't use cancellation -- check isCancelled safely returns false.
   * Do NOT call cancel() on this instance.
   */
  static readonly NONE = new CancellationToken();
}
