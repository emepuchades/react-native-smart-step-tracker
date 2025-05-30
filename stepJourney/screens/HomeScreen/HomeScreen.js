import React, { useEffect, useState } from "react";
import {
  DeviceEventEmitter,
  NativeModules,
  PermissionsAndroid,
  ScrollView,
  StyleSheet,
  Text,
} from "react-native";
import StepSummary from "../../components/StepSummary";

const { StepTrackerModule } = NativeModules;

export default function HomeScreen() {
  const [stats, setStats] = useState({
    steps: 0,
    calories: 0,
    distance: 0,
    progress: 0,
  });
  const [history, setHistory] = useState({});

  useEffect(() => {
    StepTrackerModule.startTracking();
    
    console.log("JS recibe pasos:", stats);

    const sub = DeviceEventEmitter.addListener("onStepStats", (data) => {
      setStats({
        steps: data.steps,
        calories: data.calories,
        distance: data.distance,
        progress: data.progress,
      });
    });

    StepTrackerModule.getTodayStats().then(setStats).catch(console.warn);
    StepTrackerModule.getStepsHistory().then(setHistory).catch(console.warn);

    return () => {
      sub.remove();
      StepTrackerModule.stopTracking();
    };
  }, []);

  return (
    <ScrollView style={styles.container}>
      <Text style={styles.header}>Pasos de Hoy</Text>
      <Text style={styles.data}>👣 Pasos: {stats.steps.toFixed(0)}</Text>
      <Text style={styles.data}>🔥 Calorías: {stats.calories.toFixed(1)}</Text>
      <Text style={styles.data}>
        📏 Distanncia: {stats.distance.toFixed(2)} km
      </Text>
      <Text style={styles.data}>
        🎯 Progreso: {stats.progress.toFixed(1)} %
      </Text>

      <ScrollView style={styles.container}>
        <Text style={styles.header}>Pasos de Hoy</Text>
        <StepSummary />
      </ScrollView>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, padding: 16, backgroundColor: "#fff" },
  header: { fontSize: 22, fontWeight: "bold", marginVertical: 12 },
  data: { fontSize: 18, marginBottom: 8 },
  history: { fontSize: 16, marginBottom: 4, color: "#555" },
});
