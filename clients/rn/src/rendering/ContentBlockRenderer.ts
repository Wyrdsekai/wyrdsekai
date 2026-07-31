/**
 * Content block renderer interface and registry.
 * Zone-type renderer packs (npm packages) implement ContentBlockRenderer
 * and register into the global registry.
 */

import React from 'react';
import { ContentBlock } from '../protocol/models';

export interface ContentBlockRenderer {
  canRender(format: string): boolean;
  render(block: ContentBlock): React.ReactNode;
}

class ContentBlockRegistryImpl {
  private renderers: ContentBlockRenderer[] = [];
  private fallback: ContentBlockRenderer;

  constructor() {
    this.fallback = {
      canRender: () => true,
      render: (block) => {
        if (!block.fallback) return null;
        // Return null here — actual React rendering happens in the component
        // This is a data-level fallback indicator
        return null;
      },
    };
  }

  register(renderer: ContentBlockRenderer): void {
    this.renderers.push(renderer);
  }

  findRenderer(format: string): ContentBlockRenderer {
    return this.renderers.find(r => r.canRender(format)) ?? this.fallback;
  }

  canRenderRich(format: string): boolean {
    return this.renderers.some(r => r.canRender(format));
  }
}

/** Global content block registry. Zone renderer packs register here. */
export const ContentBlockRegistry = new ContentBlockRegistryImpl();
