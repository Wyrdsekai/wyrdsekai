/** React Navigation type definitions for the root stack. */

export type RootStackParamList = {
  Welcome: undefined;
  FirstRun: undefined;
  Login: { serverUrl: string; deviceToken: string };
  Birth: undefined;
  Connect: undefined;
  /** — the zone bank (your servers) + relay auto-attempt login. */
  Servers: undefined;
  /** — opt-in directory discovery ("Find a zone"). */
  FindZone: undefined;
  Room: undefined;
  Standalone: undefined;
  Inventory: undefined;
  Settings: undefined;
  ModelDownload: undefined;
  WebNodeDashboard: undefined;
  Household: undefined;
  Study: {
    mounts?: Record<string, string> | string;
    apps?: Record<string, string> | string;
    scheduleItems?: string[];
    ageBracket?: string;
    studyStore?: import('../engine/study/StudyStore').StudyStore;
    userDid?: string;
    onSay?: (text: string) => void;
  } | undefined;
};

declare global {
  namespace ReactNavigation {
    interface RootParamList extends RootStackParamList {}
  }
}
