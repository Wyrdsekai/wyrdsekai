/**
 * React context provider that exposes the inference service singletons
 * to all screens via useInference().
 *
 * The pattern mirrors App.tsx's WsContext — one long-lived ref per
 * service, provided through React context.
 */

import React, { useRef, useEffect } from 'react';
import { LlamaService } from './LlamaService';
import { InferenceRouter } from './InferenceRouter';
import { ModelManager } from './ModelManager';
import { useInferenceStore } from '../state/inferenceStore';

// Resolve platform-specific models directory.
// Falls back to /tmp for web/test environments where RNFS is absent.
let modelsDir = '/tmp/wyrdsekai-models';
try {
  const RNFS = require('react-native-fs');
  modelsDir = `${RNFS.DocumentDirectoryPath}/models`;
} catch {
  // Not in RN environment — keep fallback
}

interface InferenceServices {
  llamaService: LlamaService;
  inferenceRouter: InferenceRouter;
  modelManager: ModelManager;
}

const InferenceContext = React.createContext<InferenceServices | null>(null);

/** Hook to access inference services. Must be used within InferenceProvider. */
export const useInference = (): InferenceServices => {
  const ctx = React.useContext(InferenceContext);
  if (!ctx) throw new Error('useInference must be used within InferenceProvider');
  return ctx;
};

/**
 * Wraps children with access to LlamaService, InferenceRouter, and
 * ModelManager singletons. Also keeps the inferenceStore's activeBackend
 * in sync with the actual router state.
 */
export const InferenceProvider: React.FC<{ children: React.ReactNode }> = ({
  children,
}) => {
  const llamaRef = useRef(new LlamaService());
  const modelManagerRef = useRef(new ModelManager(modelsDir));
  const routerRef = useRef(new InferenceRouter(llamaRef.current));

  const setActiveBackend = useInferenceStore((s) => s.setActiveBackend);

  // Poll the router to keep Zustand store in sync with native model state.
  // A 1-second interval is sufficient — model load/unload are infrequent.
  useEffect(() => {
    const interval = setInterval(() => {
      setActiveBackend(routerRef.current.getActiveBackend());
    }, 1000);
    return () => clearInterval(interval);
  }, [setActiveBackend]);

  const services: InferenceServices = {
    llamaService: llamaRef.current,
    inferenceRouter: routerRef.current,
    modelManager: modelManagerRef.current,
  };

  return (
    <InferenceContext.Provider value={services}>
      {children}
    </InferenceContext.Provider>
  );
};
