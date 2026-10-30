import { Platform } from 'react-native';
import { requireNativeModule } from 'expo-modules-core';

// ---- Android: the real floating circle (native Kotlin module) ----
let Native = null;
let lastError = '';
if (Platform.OS === 'android') {
  try { Native = requireNativeModule('TasksOverlay'); } catch (e) { lastError = 'Native module not found: ' + (e && e.message ? e.message : e); }
}
// Every native call is wrapped so a native error shows on screen instead of closing the app.
const safe = (fn, fallback) => {
  try { return fn(); } catch (e) { lastError = (e && e.message) ? e.message : String(e); return fallback; }
};

// ---- Browser preview: no floating circle on the web, so just keep the list in localStorage ----
const get = (k, d) => { try { const v = window.localStorage.getItem('tasks_' + k); return v === null ? d : v; } catch (e) { return d; } };
const set = (k, v) => { try { window.localStorage.setItem('tasks_' + k, v); } catch (e) { /* ignore */ } };
const webTasks = () => { try { const a = JSON.parse(get('list', '[]')); return Array.isArray(a) ? a : []; } catch (e) { return []; } };

const web = {
  supported: true,
  error: () => '',
  canDrawOverlays: () => true,
  requestOverlayPermission: () => {},
  start: () => { set('enabled', '1'); return true; },
  stop: () => set('enabled', '0'),
  isEnabled: () => get('enabled', '0') === '1',
  isRunning: () => get('enabled', '0') === '1',
  getTasks: () => webTasks(),
  addTask: (text, date) => {
    const now = Date.now();
    set('list', JSON.stringify([...webTasks(), { id: now.toString(36) + Math.random().toString(36).slice(2, 6), text, date: date || '', ts: now }]));
  },
  deleteTask: (id) => set('list', JSON.stringify(webTasks().filter((t) => t.id !== id))),
  clearTasks: () => set('list', '[]'),
};

const android = {
  supported: !!Native,
  error: () => lastError,
  canDrawOverlays: () => safe(() => Native.canDrawOverlays(), false),
  requestOverlayPermission: () => safe(() => Native.requestOverlayPermission(), undefined),
  /** Starts the floating circle. Returns false if the overlay permission is missing. */
  start: () => safe(() => Native.start(), false),
  stop: () => safe(() => Native.stop(), undefined),
  isEnabled: () => safe(() => Native.isEnabled(), false),
  isRunning: () => safe(() => Native.isRunning(), false),
  /** The to-do list: [{ id, text, date (yyyy-MM-dd or ""), ts }] */
  getTasks: () => safe(() => { const a = JSON.parse(Native.getTasks()); return Array.isArray(a) ? a : []; }, []),
  addTask: (text, date) => safe(() => Native.addTask(text, date || ''), undefined),
  deleteTask: (id) => safe(() => Native.deleteTask(id), undefined),
  clearTasks: () => safe(() => Native.clearTasks(), undefined),
};

export default Platform.OS === 'web' ? web : android;
