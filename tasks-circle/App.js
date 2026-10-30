import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  Alert, AppState, PermissionsAndroid, Platform, Pressable, ScrollView,
  StatusBar, StyleSheet, Switch, Text, TextInput, View,
} from 'react-native';
import TasksOverlay from './modules/tasks-overlay';

const C = {
  paper: '#edeee3', card: '#f7f6ee', ink: '#1f241f', soft: '#565f52', hint: '#9a9d8f',
  teal: '#1f6f6b', tealDark: '#16514e', line: '#d8d8c8', red: '#a33a3a',
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
// earliest date first; tasks without a date go last
const sortTasks = (list) => [...list].sort((a, b) => (a.date || '9999-99-99').localeCompare(b.date || '9999-99-99'));

// Alert.alert does nothing in the browser, so use window.alert / confirm there.
function ask(title, message, buttons) {
  if (Platform.OS !== 'web') { Alert.alert(title, message, buttons); return; }
  const text = title + (message ? '\n\n' + message : '');
  const action = (buttons || []).find((b) => b.onPress);
  if (action && buttons.length > 1) { if (window.confirm(text)) action.onPress(); }
  else window.alert(text);
}

function Main() {
  const [tasks, setTasks] = useState([]);
  const [on, setOn] = useState(false);
  const [text, setText] = useState('');
  const [date, setDate] = useState(todayStr());
  const [msg, setMsg] = useState('');
  const wantOn = useRef(false);   // waiting for the overlay permission

  const reload = () => setTasks(sortTasks(TasksOverlay.getTasks()));

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
    setTasks(sortTasks(TasksOverlay.getTasks()));
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
    reload();
  };

  const removeTask = (id) => { TasksOverlay.deleteTask(id); reload(); };

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
        <Text style={s.eyebrow}>TASKS</Text>
        <Text style={s.title}>Things to do.</Text>
        <Text style={s.sub}>Turn on the circle, then tap it from any app to see your list. Pick a date, tap + to add a task and − to remove one.</Text>

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

        <View style={s.list}>
          {tasks.length === 0 ? <Text style={s.empty}>No tasks yet</Text> : tasks.map((t) => (
            <View key={t.id} style={s.task}>
              <Pressable onPress={() => removeTask(t.id)} style={[s.round, s.minusBtn]} hitSlop={8}>
                <View style={s.minus} />
              </Pressable>
              <View style={s.taskCol}>
                <Text style={s.taskText}>{t.text}</Text>
                {t.date ? <Text style={[s.taskDate, dayDiff(t.date) < 0 && { color: C.red }]}>{dateLabel(t.date)}</Text> : null}
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
            <Text style={s.dateLbl}>Date</Text>
            <Pressable onPress={() => setDate(shiftDate(date, -1))} hitSlop={10}><Text style={s.step}>‹</Text></Pressable>
            <Pressable onPress={() => setDate(todayStr())} hitSlop={6}><Text style={s.dateVal}>{dateLabel(date)}</Text></Pressable>
            <Pressable onPress={() => setDate(shiftDate(date, 1))} hitSlop={10}><Text style={s.step}>›</Text></Pressable>
          </View>
        </View>
      </ScrollView>
    </View>
  );
}

const s = StyleSheet.create({
  root: { flex: 1, backgroundColor: C.paper },
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
  dateLbl: { color: C.soft, fontSize: 13, marginRight: 12 },
  step: { color: C.teal, fontSize: 24, fontWeight: '600', paddingHorizontal: 10, lineHeight: 26 },
  dateVal: { color: C.ink, fontSize: 15, fontWeight: '600', minWidth: 92, textAlign: 'center' },
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
