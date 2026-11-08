import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  Alert, AppState, PermissionsAndroid, Platform, Pressable, ScrollView,
  StatusBar, StyleSheet, Switch, Text, TextInput, View,
} from 'react-native';
import TasksOverlay from './modules/tasks-overlay';

const C = {
  paper: '#ffffff', card: '#f4f4f4', ink: '#000000', soft: '#555555', hint: '#999999',
  teal: '#000000', tealDark: '#000000', line: '#d6d6d6', red: '#000000',
};

// ---- dates (day only, no time) ----
const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
const pad = (n) => String(n).padStart(2, '0');
const toStr = (d) => d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate());
const todayStr = () => toStr(new Date());
const parts = (str) => str.split('-').map(Number);
const shiftDate = (str, n) => { const [y, m, d] = parts(str); return toStr(new Date(y, m - 1, d + n)); };
const dayDiff = (str) => {
  const [y, m, d] = parts(str);
  const t = new Date();
  return Math.round((new Date(y, m - 1, d) - new Date(t.getFullYear(), t.getMonth(), t.getDate())) / 86400000);
};
const dateLabel = (str) => {
  if (!str) return '';
  const n = dayDiff(str);
  if (n === 0) return 'Today';
  if (n === 1) return 'Tomorrow';
  if (n === -1) return 'Yesterday';
  const [y, m, d] = parts(str);
  return d + ' ' + MONTHS[m - 1] + (y !== new Date().getFullYear() ? ' ' + y : '');
};
// Dates can be picked from today up to 3 months ahead.
const MAX_MONTHS = 3;
const MONTH_NAMES = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'];
const DOW = ['S', 'M', 'T', 'W', 'T', 'F', 'S'];
const maxDateStr = () => {
  const t = new Date();
  const last = new Date(t.getFullYear(), t.getMonth() + MAX_MONTHS + 1, 0).getDate();
  return toStr(new Date(t.getFullYear(), t.getMonth() + MAX_MONTHS, Math.min(t.getDate(), last)));
};

// Alert.alert does nothing in the browser, so use window.alert / confirm there.
function ask(title, message, buttons) {
  if (Platform.OS !== 'web') { Alert.alert(title, message, buttons); return; }
  const text = title + (message ? '\n\n' + message : '');
  const action = (buttons || []).find((b) => b.onPress);
  if (action && buttons.length > 1) { if (window.confirm(text)) action.onPress(); }
  else window.alert(text);
}

// Month calendar: today up to 3 months ahead can be picked, everything else is greyed out.
function Calendar({ value, onPick }) {
  const t = new Date();
  const today = todayStr();
  const max = maxDateStr();
  const startOff = () => {
    if (!value) return 0;
    const [y, m] = parts(value);
    return Math.min(MAX_MONTHS, Math.max(0, (y - t.getFullYear()) * 12 + (m - 1 - t.getMonth())));
  };
  const [off, setOff] = useState(startOff);
  const first = new Date(t.getFullYear(), t.getMonth() + off, 1);
  const y = first.getFullYear();
  const m = first.getMonth();
  const days = new Date(y, m + 1, 0).getDate();
  const cells = [];
  for (let i = 0; i < first.getDay(); i++) cells.push(null);
  for (let d = 1; d <= days; d++) cells.push(toStr(new Date(y, m, d)));
  while (cells.length % 7) cells.push(null);
  const rows = [];
  for (let i = 0; i < cells.length; i += 7) rows.push(cells.slice(i, i + 7));
  return (
    <View style={s.cal}>
      <View style={s.calHead}>
        <Pressable disabled={off <= 0} onPress={() => setOff(off - 1)} hitSlop={8}><Text style={[s.step, off <= 0 && s.dim]}>‹</Text></Pressable>
        <Text style={s.calTitle}>{MONTH_NAMES[m]} {y}</Text>
        <Pressable disabled={off >= MAX_MONTHS} onPress={() => setOff(off + 1)} hitSlop={8}><Text style={[s.step, off >= MAX_MONTHS && s.dim]}>›</Text></Pressable>
      </View>
      <View style={s.calRow}>{DOW.map((l, i) => <Text key={i} style={s.calDow}>{l}</Text>)}</View>
      {rows.map((r, ri) => (
        <View key={ri} style={s.calRow}>
          {r.map((ds, ci) => {
            if (!ds) return <View key={ci} style={s.calCell} />;
            const ok = ds >= today && ds <= max;
            const sel = ds === value;
            return (
              <Pressable key={ci} disabled={!ok} onPress={() => onPick(ds)} style={s.calCell}>
                <View style={[s.calDay, sel && s.calSel, ds === today && !sel && s.calToday]}>
                  <Text style={[s.calNum, !ok && s.dim, sel && { color: C.paper }]}>{Number(ds.slice(8))}</Text>
                </View>
              </Pressable>
            );
          })}
        </View>
      ))}
    </View>
  );
}

function Main() {
  const [tasks, setTasks] = useState([]);
  const [ideas, setIdeas] = useState([]);
  const [tab, setTab] = useState('tasks');
  const [ideaText, setIdeaText] = useState('');
  const [on, setOn] = useState(false);
  const [text, setText] = useState('');
  const [date, setDate] = useState('');   // optional: empty = this task has no date
  const [msg, setMsg] = useState('');
  const [showCal, setShowCal] = useState(false);
  const wantOn = useRef(false);   // waiting for the overlay permission

  const reload = () => { setTasks(TasksOverlay.getTasks()); setIdeas(TasksOverlay.getIdeas()); };

  const askNotifications = async () => {
    if (Platform.OS === 'android' && Platform.Version >= 33) {
      try { await PermissionsAndroid.request(PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS); } catch (e) { /* optional */ }
    }
  };

  const turnOn = useCallback(async () => {
    if (!TasksOverlay.canDrawOverlays()) {
      ask(
        'Allow display over other apps',
        'The floating circle needs the "Display over other apps" permission. Turn it on for Tasks, then come back.',
        [
          { text: 'Cancel', style: 'cancel' },
          { text: 'Open settings', onPress: () => { wantOn.current = true; TasksOverlay.requestOverlayPermission(); } },
        ]
      );
      return;
    }
    await askNotifications();
    const started = !!TasksOverlay.start();
    setOn(started);
    if (!started && TasksOverlay.error()) setMsg('Could not start the circle: ' + TasksOverlay.error());
  }, []);

  const turnOff = useCallback(() => {
    wantOn.current = false;
    TasksOverlay.stop();
    setOn(false);
  }, []);

  // Sync with the native side whenever the app comes back to the front.
  const refresh = useCallback(() => {
    setTasks(TasksOverlay.getTasks());
    setIdeas(TasksOverlay.getIdeas());
    if (wantOn.current && TasksOverlay.canDrawOverlays()) {
      wantOn.current = false;
      turnOn();
      return;
    }
    const enabled = TasksOverlay.isEnabled();
    if (enabled && TasksOverlay.canDrawOverlays() && !TasksOverlay.isRunning()) TasksOverlay.start();
    setOn(enabled && TasksOverlay.canDrawOverlays());
  }, [turnOn]);

  useEffect(() => {
    refresh();
    const sub = AppState.addEventListener('change', (st) => { if (st === 'active') refresh(); });
    return () => sub.remove();
  }, [refresh]);

  const addTask = () => {
    const t = text.trim();
    if (!t) return;
    TasksOverlay.addTask(t, date);
    setText('');
    setDate('');
    setShowCal(false);
    reload();
  };

  const removeTask = (id) => { TasksOverlay.deleteTask(id); reload(); };

  // swap a task with its neighbour (dir -1 = up, +1 = down)
  const moveTask = (i, dir) => {
    const other = tasks[i + dir];
    if (!other) return;
    TasksOverlay.swapTasks(tasks[i].id, other.id);
    reload();
  };

  if (!TasksOverlay.supported) {
    return (
      <View style={[s.root, s.center]}>
        <Text style={s.title}>Tasks</Text>
        <Text style={[s.small, { textAlign: 'center' }]}>
          {Platform.OS === 'android' ? 'The floating circle could not start.' : 'The floating circle only works on Android.'}
        </Text>
        {TasksOverlay.error() ? <Text selectable style={[s.small, { textAlign: 'center', marginTop: 12, color: C.red }]}>{TasksOverlay.error()}</Text> : null}
      </View>
    );
  }

  return (
    <View style={s.root}>
      <StatusBar barStyle="dark-content" backgroundColor={C.paper} />
      <ScrollView contentContainerStyle={s.scroll} keyboardShouldPersistTaps="handled" showsVerticalScrollIndicator={false}>
        <Text style={s.eyebrow}>LOOSE · TASKS</Text>
        <Text style={s.title}>Things to do.</Text>
        <Text style={s.sub}>Turn on the circle, then tap it from any app to see your list. Tap + to add a task (a date up to 3 months ahead is optional), − to remove one, and ▲ ▼ to move important tasks to the top.</Text>

        <View style={s.circleRow}>
          <View style={{ flex: 1, paddingRight: 12 }}>
            <Text style={s.circleTitle}>Floating circle</Text>
            <Text style={s.small}>{on ? 'On: tap the circle to see your tasks' : 'Off: the circle is hidden'}</Text>
          </View>
          <Switch
            value={on}
            onValueChange={(v) => (v ? turnOn() : turnOff())}
            trackColor={{ false: C.line, true: C.teal }}
            thumbColor={C.card}
          />
        </View>
        {msg ? <Text selectable style={[s.small, { color: C.red, marginBottom: 10 }]}>{msg}</Text> : null}

        <View style={s.tabs}>
          {['tasks', 'ideas'].map((k) => (
            <Pressable key={k} onPress={() => setTab(k)} style={[s.tabBtn, tab === k && s.tabOn]}>
              <Text style={[s.tabTxt, tab === k && { color: C.paper }]}>{k === 'tasks' ? 'Tasks' : 'Ideas'}</Text>
            </Pressable>
          ))}
        </View>

        {tab === 'ideas' ? (
          <View style={s.list}>
            {ideas.length === 0 ? <Text style={s.empty}>No ideas yet. Tap the circle and write one.</Text> : ideas.map((i) => (
              <View key={i.id} style={s.task}>
                <View style={s.taskCol}>
                  <Text style={s.taskText}>{i.text}</Text>
                  <Text style={s.taskDate}>{new Date(i.ts).toLocaleDateString()}</Text>
                </View>
                <Pressable onPress={() => { TasksOverlay.deleteIdea(i.id); reload(); }} hitSlop={8}><Text style={s.arrowTxt}>✕</Text></Pressable>
              </View>
            ))}
            <View style={[s.task, s.addRow]}>
              <TextInput style={s.input} value={ideaText} onChangeText={setIdeaText} placeholder="Write your idea…" placeholderTextColor={C.hint}
                onSubmitEditing={() => { const t = ideaText.trim(); if (t) { TasksOverlay.addIdea(t); setIdeaText(''); reload(); } }} returnKeyType="done" />
            </View>
          </View>
        ) : null}

        <View style={[s.list, tab !== 'tasks' && { display: 'none' }]}>
          {tasks.length === 0 ? <Text style={s.empty}>No tasks yet</Text> : tasks.map((t, i) => (
            <View key={t.id} style={s.task}>
              <Pressable onPress={() => removeTask(t.id)} style={[s.round, s.minusBtn]} hitSlop={8}>
                <View style={s.minus} />
              </Pressable>
              <View style={s.taskCol}>
                <Text style={s.taskText}>{t.text}</Text>
                {t.date ? <Text style={[s.taskDate, dayDiff(t.date) < 0 && { color: C.ink, fontWeight: '700' }]}>{dateLabel(t.date)}</Text> : null}
              </View>
              <View style={s.arrows}>
                <Pressable disabled={i === 0} onPress={() => moveTask(i, -1)} hitSlop={6} style={[s.arrow, i === 0 && s.dim]}><Text style={s.arrowTxt}>▲</Text></Pressable>
                <Pressable disabled={i === tasks.length - 1} onPress={() => moveTask(i, 1)} hitSlop={6} style={[s.arrow, i === tasks.length - 1 && s.dim]}><Text style={s.arrowTxt}>▼</Text></Pressable>
              </View>
            </View>
          ))}
          <View style={[s.task, s.addRow]}>
            <TextInput
              style={s.input}
              value={text}
              onChangeText={setText}
              placeholder="Add a task…"
              placeholderTextColor={C.hint}
              onSubmitEditing={addTask}
              returnKeyType="done"
            />
            <Pressable onPress={addTask} style={[s.round, s.plusBtn]} hitSlop={8}>
              <View style={s.minus} />
              <View style={[s.minus, s.plusV]} />
            </Pressable>
          </View>
          <View style={s.dateRow}>
            {date ? (
              <>
                <Text style={s.dateLbl}>Date</Text>
                <Pressable onPress={() => setShowCal(!showCal)} hitSlop={8}><Text style={s.dateVal}>{dateLabel(date)} ▾</Text></Pressable>
                <Pressable onPress={() => { setDate(''); setShowCal(false); }} hitSlop={8}><Text style={s.clear}>Clear</Text></Pressable>
              </>
            ) : (
              <Pressable onPress={() => setShowCal(!showCal)} hitSlop={8}><Text style={s.addDate}>+ Add a date (optional, up to 3 months ahead)</Text></Pressable>
            )}
          </View>
          {showCal ? <Calendar value={date} onPick={(d) => { setDate(d); setShowCal(false); }} /> : null}
        </View>
      </ScrollView>
    </View>
  );
}

const s = StyleSheet.create({
  root: { flex: 1, backgroundColor: C.paper },
  tabs: { flexDirection: 'row', marginBottom: 12 },
  tabBtn: { flex: 1, paddingVertical: 8, alignItems: 'center', borderWidth: 1, borderColor: C.line, marginRight: 6, borderRadius: 3 },
  tabOn: { backgroundColor: C.ink, borderColor: C.ink },
  tabTxt: { color: C.ink, fontSize: 13, fontWeight: '600' },
  center: { alignItems: 'center', justifyContent: 'center', padding: 24 },
  scroll: { paddingHorizontal: 20, paddingTop: (StatusBar.currentHeight || 24) + 24, paddingBottom: 60 },
  eyebrow: { fontSize: 12, letterSpacing: 1.6, color: C.tealDark, marginBottom: 6, fontWeight: '600' },
  title: { fontWeight: '700', fontSize: 34, color: C.ink, marginBottom: 8 },
  sub: { color: C.soft, fontSize: 15, lineHeight: 21, marginBottom: 20 },
  small: { color: C.soft, fontSize: 12.5, lineHeight: 17 },
  circleRow: {
    flexDirection: 'row', alignItems: 'center', backgroundColor: C.card, borderColor: C.line, borderWidth: 1,
    borderRadius: 4, paddingVertical: 12, paddingHorizontal: 14, marginBottom: 18,
  },
  circleTitle: { color: C.ink, fontSize: 15, fontWeight: '600', marginBottom: 2 },
  list: { backgroundColor: C.card, borderColor: C.line, borderWidth: 1, borderRadius: 4, paddingVertical: 6 },
  empty: { color: C.soft, fontStyle: 'italic', padding: 14 },
  task: { flexDirection: 'row', alignItems: 'center', paddingHorizontal: 14, paddingVertical: 8 },
  taskCol: { flex: 1, marginLeft: 12 },
  taskText: { color: C.ink, fontSize: 16 },
  taskDate: { color: C.soft, fontSize: 12.5, marginTop: 1 },
  round: {
    width: 28, height: 28, borderRadius: 14, borderWidth: 1.5, borderColor: C.teal,
    alignItems: 'center', justifyContent: 'center',
  },
  minus: { position: 'absolute', width: 12, height: 2, borderRadius: 1, backgroundColor: C.teal },
  plusV: { width: 2, height: 12 },
  minusBtn: { borderWidth: 0, backgroundColor: 'transparent' },
  plusBtn: { borderWidth: 0, backgroundColor: 'transparent' },
  dateRow: { flexDirection: 'row', alignItems: 'center', paddingHorizontal: 14, paddingBottom: 8 },
  addDate: { color: C.soft, fontSize: 13.5 },
  clear: { color: C.soft, fontSize: 13, marginLeft: 14, textDecorationLine: 'underline' },
  dateLbl: { color: C.soft, fontSize: 13, marginRight: 12 },
  step: { color: C.teal, fontSize: 24, fontWeight: '600', paddingHorizontal: 10, lineHeight: 26 },
  dateVal: { color: C.ink, fontSize: 15, fontWeight: '600', minWidth: 92, textAlign: 'center' },
  arrows: { flexDirection: 'row', alignItems: 'center' },
  arrow: { paddingHorizontal: 9, paddingVertical: 6 },
  arrowTxt: { color: C.soft, fontSize: 14 },
  dim: { opacity: 0.3 },
  cal: { paddingHorizontal: 10, paddingBottom: 8 },
  calHead: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', marginBottom: 4 },
  calTitle: { color: C.ink, fontSize: 15, fontWeight: '600' },
  calRow: { flexDirection: 'row' },
  calDow: { flex: 1, textAlign: 'center', color: C.hint, fontSize: 12, paddingVertical: 4 },
  calCell: { flex: 1, height: 38, alignItems: 'center', justifyContent: 'center' },
  calDay: { width: 32, height: 32, borderRadius: 16, alignItems: 'center', justifyContent: 'center' },
  calSel: { backgroundColor: C.ink },
  calToday: { borderWidth: 1, borderColor: C.ink },
  calNum: { color: C.ink, fontSize: 14 },
  addRow: { borderTopWidth: 1, borderTopColor: C.line, marginTop: 4, paddingTop: 10 },
  input: { flex: 1, fontSize: 16, color: C.ink, paddingVertical: 6, paddingRight: 10 },
});

// Shows any crash on screen instead of silently closing the app.
class Boundary extends React.Component {
  state = { error: null };
  static getDerivedStateFromError(error) { return { error }; }
  render() {
    if (this.state.error) {
      return (
        <ScrollView style={s.root} contentContainerStyle={{ padding: 24, paddingTop: 60 }}>
          <Text style={s.title}>Tasks hit an error</Text>
          <Text selectable style={{ color: C.red, fontSize: 13 }}>{String(this.state.error && (this.state.error.stack || this.state.error.message || this.state.error))}</Text>
        </ScrollView>
      );
    }
    return this.props.children;
  }
}

export default function App() {
  return (
    <Boundary>
      <Main />
    </Boundary>
  );
}
