import { produce } from 'immer';
import { useSyncExternalStore } from 'react';
import { createProject, migrateProject } from '../model/defaults';
import type { Project } from '../model/types';

type Listener = () => void;

export interface Store<T> {
  get(): T;
  set(v: T | ((prev: T) => T)): void;
  subscribe(l: Listener): () => void;
}

export function createStore<T>(init: T): Store<T> {
  let state = init;
  const ls = new Set<Listener>();
  return {
    get: () => state,
    set(v) {
      const next = typeof v === 'function' ? (v as (p: T) => T)(state) : v;
      if (next === state) return;
      state = next;
      ls.forEach((l) => l());
    },
    subscribe(l) {
      ls.add(l);
      return () => ls.delete(l);
    },
  };
}

export function useStore<T>(s: Store<T>): T {
  return useSyncExternalStore(s.subscribe, s.get, s.get);
}

/* ---------------- 项目（带撤销/重做） ---------------- */

const STORAGE_KEY = 'tdjc.project.v1';
const HISTORY_LIMIT = 120;

interface ProjectState {
  project: Project;
  past: Project[];
  future: Project[];
  savedAt: number;
}

function loadInitial(): Project {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (raw) return migrateProject(JSON.parse(raw));
  } catch {
    /* ignore */
  }
  return createProject();
}

export const projectStore = createStore<ProjectState>({
  project: typeof localStorage === 'undefined' ? createProject() : loadInitial(),
  past: [],
  future: [],
  savedAt: 0,
});

let lastCoalesce: { key: string; t: number } | null = null;

export interface UpdateOpts {
  /** false：不记录历史（拖动过程中） */
  history?: boolean;
  /** 相同 key 的连续修改合并为一步撤销 */
  coalesce?: string;
}

export const projectActions = {
  get: () => projectStore.get().project,
  update(recipe: (d: Project) => void, opts: UpdateOpts = {}) {
    const st = projectStore.get();
    const next = produce(st.project, (d) => {
      recipe(d);
      d.updatedAt = new Date().toISOString();
    });
    if (next === st.project) return;
    let push = opts.history !== false;
    if (push && opts.coalesce) {
      const now = Date.now();
      if (lastCoalesce && lastCoalesce.key === opts.coalesce && now - lastCoalesce.t < 1500) push = false;
      lastCoalesce = { key: opts.coalesce, t: now };
    } else if (push) lastCoalesce = null;
    projectStore.set({
      ...st,
      project: next,
      past: push ? [...st.past, st.project].slice(-HISTORY_LIMIT) : st.past,
      future: push ? [] : st.future,
    });
  },
  /** 直接替换（不记录历史，用于拖动中） */
  replace(p: Project) {
    const st = projectStore.get();
    projectStore.set({ ...st, project: p });
  },
  /** 记录当前状态为一个撤销点 */
  checkpoint() {
    const st = projectStore.get();
    lastCoalesce = null;
    projectStore.set({ ...st, past: [...st.past, st.project].slice(-HISTORY_LIMIT), future: [] });
  },
  /** 撤销点之后没有变化时丢弃 */
  dropCheckpointIfUnchanged() {
    const st = projectStore.get();
    if (st.past.length && st.past[st.past.length - 1] === st.project) projectStore.set({ ...st, past: st.past.slice(0, -1) });
  },
  undo() {
    const st = projectStore.get();
    if (!st.past.length) return;
    lastCoalesce = null;
    projectStore.set({
      ...st,
      project: st.past[st.past.length - 1],
      past: st.past.slice(0, -1),
      future: [st.project, ...st.future],
    });
  },
  redo() {
    const st = projectStore.get();
    if (!st.future.length) return;
    lastCoalesce = null;
    projectStore.set({ ...st, project: st.future[0], past: [...st.past, st.project], future: st.future.slice(1) });
  },
  load(p: Project) {
    lastCoalesce = null;
    const st = projectStore.get();
    projectStore.set({ ...st, project: migrateProject(p), past: [...st.past, st.project].slice(-HISTORY_LIMIT), future: [] });
  },
};

export function useProject(): Project {
  return useStore(projectStore).project;
}

/* 自动保存 */
if (typeof window !== 'undefined') {
  let timer: number | undefined;
  let lastSaved: Project | null = null;
  projectStore.subscribe(() => {
    window.clearTimeout(timer);
    timer = window.setTimeout(() => {
      const st = projectStore.get();
      if (st.project === lastSaved) return;
      try {
        localStorage.setItem(STORAGE_KEY, JSON.stringify(st.project));
        lastSaved = st.project;
        projectStore.set({ ...projectStore.get(), savedAt: Date.now() });
      } catch {
        /* 存储失败（隐私模式/空间不足）时忽略 */
      }
    }, 400);
  });
}

/* ---------------- 界面状态 ---------------- */

export type Tab = 'map' | 'info' | 'varieties' | 'records' | 'summary' | 'weather' | 'fields';

export interface UIState {
  tab: Tab;
  /** 当前编辑的容器（其子节点可选择/拖动/合并） */
  activeId: string;
  /** 选中的子节点键 */
  selection: string[];
  anchorKey: string | null;
  colorBy: string;
  panelOpen: boolean;
}

export const uiStore = createStore<UIState>({
  tab: 'map',
  activeId: projectStore.get().project.field.id,
  selection: [],
  anchorKey: null,
  colorBy: 'variety',
  panelOpen: true,
});

/* ---------------- 视角 ---------------- */

export interface ViewState {
  rot: number;
  zoom: number;
  panX: number;
  panY: number;
  /** 背景拖动：平移 / 旋转 */
  mode: 'pan' | 'rotate';
  locked: boolean;
  ready: boolean;
}

const VIEW_KEY = 'tdjc.view.v1';

function loadView(): ViewState {
  const base: ViewState = { rot: 0, zoom: 20, panX: 0, panY: 0, mode: 'pan', locked: false, ready: false };
  try {
    const raw = typeof localStorage !== 'undefined' ? localStorage.getItem(VIEW_KEY) : null;
    if (raw) {
      const v = JSON.parse(raw);
      return { ...base, rot: Number(v.rot) || 0, mode: v.mode === 'rotate' ? 'rotate' : 'pan', locked: !!v.locked };
    }
  } catch {
    /* ignore */
  }
  return base;
}

export const viewStore = createStore<ViewState>(loadView());

if (typeof window !== 'undefined') {
  viewStore.subscribe(() => {
    const v = viewStore.get();
    try {
      localStorage.setItem(VIEW_KEY, JSON.stringify({ rot: v.rot, mode: v.mode, locked: v.locked }));
    } catch {
      /* ignore */
    }
  });
}
