/**
 * WebNodeContext — React context provider that manages the EphemeralNode lifecycle.
 *
 * Wrap your app (or the web-specific subtree) in <WebNodeProvider> to get
 * access to ephemeral node services via the useWebNode() hook.
 *
 * On mount:
 *   1. Detects browser capabilities and pushes them to the Zustand store
 *   2. Initializes the EphemeralNode (opens IndexedDB)
 *   3. Subscribes to node state changes and syncs them to the store
 *
 * On unmount:
 *   1. Unsubscribes from state changes
 *   2. Shuts down the node (unloads model, frees GPU memory)
 */
import React, { createContext, useContext, useEffect, useRef } from 'react';
import { EphemeralNode } from './EphemeralNode';
import { useWebNodeStore } from '../state/webNodeStore';
import { WebLLMService } from './WebLLMService';

interface WebNodeServices {
  ephemeralNode: EphemeralNode;
}

const WebNodeContext = createContext<WebNodeServices | null>(null);

/**
 * Hook to access ephemeral node services.
 * Must be called within a <WebNodeProvider>.
 */
export const useWebNode = (): WebNodeServices => {
  const ctx = useContext(WebNodeContext);
  if (!ctx) {
    throw new Error('useWebNode must be used within WebNodeProvider');
  }
  return ctx;
};

/**
 * Provider component that creates and manages the EphemeralNode instance.
 * Only initializes the node if running on a web platform.
 */
export const WebNodeProvider: React.FC<{ children: React.ReactNode }> = ({
  children,
}) => {
  const nodeRef = useRef(new EphemeralNode());
  const setNodeState = useWebNodeStore((s) => s.setNodeState);
  const setNodeError = useWebNodeStore((s) => s.setNodeError);
  const setCapabilities = useWebNodeStore((s) => s.setCapabilities);

  useEffect(() => {
    // Detect capabilities on mount
    const caps = EphemeralNode.detectCapabilities();
    setCapabilities(caps);

    // Initialize node
    const node = nodeRef.current;
    const unsub = node.onStateChange((state) => {
      setNodeState(state);
      setNodeError(node.error);
    });

    // Only initialize if we're on web platform
    if (WebLLMService.isWebPlatform()) {
      node.initialize();
    }

    return () => {
      unsub();
      node.shutdown();
    };
  }, [setNodeState, setNodeError, setCapabilities]);

  return (
    <WebNodeContext.Provider value={{ ephemeralNode: nodeRef.current }}>
      {children}
    </WebNodeContext.Provider>
  );
};
