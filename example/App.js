import React, {useEffect} from 'react';
import {SafeAreaView, Text, StyleSheet, NativeModules} from 'react-native';

const {StepTrackerModule} = NativeModules;

export default function App() {
  useEffect(() => {
    console.log('📲 App montada, iniciando tracking de pasos');
    if (StepTrackerModule && StepTrackerModule.startTracking) {
      StepTrackerModule.startTracking();
    } else {
      console.warn('StepTrackerModule no disponible');
    }
  }, []);

  return (
    <SafeAreaView style={styles.container}>
      <Text style={styles.title}>🚶 Contador de Pasos (nativo)</Text>
      <Text style={styles.subtitle}>Mira el logcat para ver los pasos</Text>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    backgroundColor: '#fff',
  },
  title: {
    fontSize: 22,
    fontWeight: 'bold',
    marginBottom: 12,
  },
  subtitle: {
    fontSize: 16,
    color: '#555',
  },
});
