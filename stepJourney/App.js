import React, { useEffect, useState } from "react";
import {
  Alert,
  DeviceEventEmitter,
  Linking,
  NativeModules,
  PermissionsAndroid,
  Platform,
  ScrollView,
  StyleSheet,
  Text,
} from "react-native";
import StepSummary from "./components/StepSummary";

const { StepTrackerModule } = NativeModules;

export default function HomeScreen() {
  const [stats, setStats] = useState({
    steps: 0,
    calories: 0,
    distance: 0,
    progress: 0,
  });

  useEffect(() => {
    let sub;
    let intervalId;
    console.log("stats", stats);
    const requestPermissions = async () => {
      if (Platform.OS !== "android") return true;

      const requests = [];
      if (Platform.Version >= 29)
        requests.push(PermissionsAndroid.PERMISSIONS.ACTIVITY_RECOGNITION);
      if (Platform.Version >= 33)
        requests.push(PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS);

      if (requests.length === 0) return true;

      const results = await PermissionsAndroid.requestMultiple(requests);

      const denied = Object.values(results).filter(
        (r) => r !== PermissionsAndroid.RESULTS.GRANTED
      );
      if (denied.length === 0) return true; // todo concedido

      const never = Object.values(results).includes(
        PermissionsAndroid.RESULTS.NEVER_ASK_AGAIN
      );

      Alert.alert(
        "Permisos necesarios",
        "Para contar tus pasos y mostrar la notificación de progreso, activa los permisos en Ajustes > Aplicaciones.",
        never
          ? [{ text: "Abrir ajustes", onPress: () => Linking.openSettings() }]
          : [{ text: "Entendido" }]
      );
      return false;
    };
    const initTracker = async () => {
      if (!(await requestPermissions())) return;

      StepTrackerModule.startTracking();
      await StepTrackerModule.ensureServiceRunning();
      setTimeout(() => {
        StepTrackerModule.getTodayStats().then(setStats).catch(() => {});
      }, 500);

      
      sub = DeviceEventEmitter.addListener("onStepStats", setStats);

      // ✅ Actualiza datos cada 5 segundos
      intervalId = setInterval(() => {
        StepTrackerModule.getTodayStats()
          .then(setStats)
          .catch(() => {});
      }, 5000);
    };

    initTracker();

    return () => {
      sub?.remove();
      if (intervalId) clearInterval(intervalId);
      // StepTrackerModule.stopTracking(); // no parar servicio
    };
  }, []);

  return (
    <ScrollView style={styles.container}>
      <Text style={styles.header}>Pasos de Hoy</Text>

      <Text style={styles.data}>👣 Pasos: {stats.steps.toFixed(0)}</Text>
      <Text style={styles.data}>🔥 Calorías: {stats.calories.toFixed(1)}</Text>
      <Text style={styles.data}>
        📏 Distancia: {stats.distance.toFixed(2)} km
      </Text>
      <Text style={styles.data}>
        🎯 Progreso: {stats.progress.toFixed(1)} %
      </Text>

      <StepSummary stats={stats} />
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, padding: 16, backgroundColor: "#fff" },
  header: { fontSize: 22, fontWeight: "bold", marginVertical: 12 },
  data: { fontSize: 18, marginBottom: 8 },
});
