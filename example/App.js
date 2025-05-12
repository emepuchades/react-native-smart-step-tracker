import React, { useEffect, useState } from 'react';
import {
  View,
  Text,
  NativeModules,
  NativeEventEmitter,
  StyleSheet,
  Platform,
  PermissionsAndroid,
} from 'react-native';

const { StepTrackerModule } = NativeModules;

export default function App() {
  const [steps, setSteps] = useState(0);

  async function requestPermission() {
    if (Platform.OS === 'android' && Platform.Version >= 29) {
      const granted = await PermissionsAndroid.request(
        PermissionsAndroid.PERMISSIONS.ACTIVITY_RECOGNITION
      );
      console.log('Permiso ACTIVITY_RECOGNITION:', granted);
    }
  }

  useEffect(() => {
    requestPermission();

    const eventEmitter = new NativeEventEmitter(StepTrackerModule);
    const subscription = eventEmitter.addListener('onStep', (event) => {
      const currentSteps = Math.floor(event.steps);
      setSteps(currentSteps);
    });

    if (StepTrackerModule.getStepsHistory) {
      StepTrackerModule.getStepsHistory()
        .then((history) => {
          console.log('📜 Historial de pasos:', history);
        })
        .catch((err) => {
          console.log('Error al obtener historial', err);
        });
    }

    //StepTrackerModule.dumpRawPrefs().then(data => {
      //console.log("📦 SharedPreferences completas:", data);
    //});

    StepTrackerModule.startTracking();

    return () => {
      console.log('🧹 Limpiando listener de pasos');
      subscription.remove();
    };
  }, []);

  return (
    <View style={styles.container}>
      <Text style={styles.title}>👣 Pasos detectados:</Text>
      <Text style={styles.count}>{steps}</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#111',
    alignItems: 'center',
    justifyContent: 'center',
  },
  title: {
    fontSize: 22,
    color: '#ccc',
    marginBottom: 16,
  },
  count: {
    fontSize: 48,
    color: '#4CAF50',
  },
});
