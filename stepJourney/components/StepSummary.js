// StepSummary.js
import React, { useEffect, useState } from "react";
import {
  ActivityIndicator,
  ScrollView,
  StyleSheet,
  Text,
  TouchableOpacity,
  View,
  NativeModules,
} from "react-native";

const { StepTrackerModule } = NativeModules;

const StepSummary = ({stats}) => {
  const [mode, setMode] = useState("day");
  const [date, setDate] = useState(new Date());
  const [data, setData] = useState({});
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    load();
  }, [mode, date, stats]);

  useEffect(() => {
    load();
  }, []);

  const load = async () => {
    setLoading(true);
    try {
      if (mode === "day") {
        const iso = date.toISOString().slice(0, 10);
        const hourly = await StepTrackerModule.getStepsByHourHistory(iso);
        setData(hourly);
        console.log("data", data);
      } else {
        const history = await StepTrackerModule.getStepsHistory();
        const tmp = {};
        for (let i = 6; i >= 0; i--) {
          const d = new Date(date);
          d.setDate(date.getDate() - i);
          const iso = d.toISOString().slice(0, 10);
          tmp[iso] = history[iso] || 0;
        }
        setData(tmp);
        console.log("data", data);
      }
    } catch (e) {
      console.warn("StepSummary:", e);
    } finally {
      setLoading(false);
    }
  };

  const entries = Object.entries(data);
  const max = Math.max(...entries.map(([, v]) => v), 1);
  const Bar = ({ value }) => <View style={[styles.bar, { width: `30%` }]} />;

  const Row = ({ label, value }) => (
    <View style={styles.row}>
      <Text style={styles.label}>{label}</Text>
      <Bar value={value} />
      <Text style={styles.value}>{value.toFixed(0)}</Text>
    </View>
  );

  return (
    <View style={styles.wrapper}>
      <View style={styles.headerRow}>
        <Text style={styles.header}>
          {mode === "day"
            ? `📅 ${date.toISOString().slice(0, 10)}`
            : "📆 Últimos 7 días"}
        </Text>
        <TouchableOpacity
          onPress={() => setMode(mode === "day" ? "week" : "day")}
        >
          <Text style={styles.switch}>
            Cambiar a {mode === "day" ? "vista semanal" : "vista diaria"}
          </Text>
        </TouchableOpacity>
      </View>

      {loading ? (
        <ActivityIndicator style={{ marginTop: 20 }} />
      ) : (
        <ScrollView>
          {mode === "day"
            ? entries
                .sort(([a], [b]) => parseInt(a) - parseInt(b))
                .filter(([, v]) => v > 0) 
                .map(([h, v]) => (
                  <Row key={h} label={`${h.padStart(2, "0")}h`} value={v} />
                ))
            : entries.map(([iso, v]) => (
                <Row key={iso} label={iso.slice(5)} value={v} />
              ))}
        </ScrollView>
      )}
    </View>
  );
};

export default StepSummary;

const styles = StyleSheet.create({
  wrapper: { marginTop: 24 },
  headerRow: {
    flexDirection: "row",
    justifyContent: "space-between",
    marginBottom: 12,
    paddingHorizontal: 4,
  },
  header: { fontSize: 18, fontWeight: "bold", color: "#333" },
  switch: { color: "#1976D2" },
  row: { flexDirection: "row", alignItems: "center", marginBottom: 6 },
  label: { width: 60, fontSize: 14, color: "#333" },
  bar: {
    height: 10,
    backgroundColor: "#4CAF50",
    borderRadius: 4,
    marginHorizontal: 8,
    flexGrow: 1,
  },
  value: { width: 60, textAlign: "right", fontSize: 14, color: "#333" },
});
