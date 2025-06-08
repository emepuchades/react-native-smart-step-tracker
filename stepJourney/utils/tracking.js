import { Alert, Linking, PermissionsAndroid, Platform } from "react-native";
import { NativeModules, DeviceEventEmitter } from "react-native";

const { StepTrackerModule } = NativeModules;

export async function requestPermissions() {
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

  if (denied.length === 0) return true;

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
}

export async function loadStats(setStats, source = "manual") {
  try {
    const stats = await StepTrackerModule.getTodayStats();
    console.log(
      `[${source}] → Pasos: ${stats.steps}, Cal: ${stats.calories}, Dist: ${stats.distance}, Prog: ${stats.progress}`
    );
    setStats(stats);
  } catch (e) {
    console.warn(`[${source}] getTodayStats failed`, e);
  }
}

export function subscribeToStepUpdates(setStats) {
  return DeviceEventEmitter.addListener("onStepStats", (newStats) => {
    console.log(`[onStepStats] evento recibido:`, newStats);
    setStats(newStats);
  });
}
