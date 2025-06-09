import React, {useEffect, useState} from 'react';
import {
  NativeModules,
  ScrollView,
  StyleSheet,
  Text,
  View,
  TouchableOpacity,
} from 'react-native';
import StepSummary from '../../components/StepSummary';
import Card from '../../components/Card/Card';
import StatsHeaderCard from '../../components/StatsHeaderCard/StatsHeaderCard';
import {
  requestPermissions,
  loadStats,
  subscribeToStepUpdates,
} from '../../utils/tracking';
import CircleBar from '../../components/CircleBar/CircleBar';
import BottomNavigation from '../../components/BottomNavigation/BottomNavigation';

const {StepTrackerModule} = NativeModules;

export default function HomeScreen() {
  const [stats, setStats] = useState({
    steps: 0,
    calories: 0,
    distance: 0,
    progress: 0,
  });

  const handleMonth = monthIndex => {
    const months = ['Enero', 'Febrero', 'Marzo', 'Abril', 'Mayo', 'Junio'];
    return months[monthIndex] || 'Mes Desconocido';
  };
  const [historial, setHistorial] = useState('');
  const today = new Date().getDay();
  const month = handleMonth(new Date().getMonth());

  useEffect(() => {
    let sub;

    const initTracker = async () => {
      if (!(await requestPermissions())) return;

      StepTrackerModule.startTracking();
      await StepTrackerModule.ensureServiceRunning();
      StepTrackerModule.scheduleBackgroundSync();

      setTimeout(() => loadStats(setStats, 'init'), 800);

      sub = subscribeToStepUpdates(setStats);

      StepTrackerModule.getPrefs()
        .then(jsonString => {
          const prefs = JSON.parse(jsonString);
          setHistorial(JSON.stringify(prefs, null, 2));
        })
        .catch(err => {
          console.error('Error leyendo preferencias:', err);
        });
    };

    initTracker();
    return () => sub?.remove();
  }, []);

  return (
    <>
      <ScrollView style={styles.container}>
        <Card>
          <View style={styles.progressHeader}>
            <View>
              <Text style={styles.progressTitle}>Today's Progress</Text>
              <Text style={styles.progressDate}>
                {month} {today}
              </Text>
            </View>
            <TouchableOpacity style={styles.detailsButton}>
              <Text style={styles.detailsButtonText}>View Details </Text>
            </TouchableOpacity>
          </View>
          <CircleBar steps={stats.steps.toFixed(0)} />
        </Card>

        <Card isRow>
          <StatsHeaderCard
            icon="🔥"
            stats={stats.calories.toFixed(1)}
            title="Calorías"
          />
          <StatsHeaderCard
            icon="📏 "
            stats={stats.distance.toFixed(2)}
            title="Distancia"
          />
          <StatsHeaderCard
            icon="🎯"
            stats={stats.progress.toFixed(1)}
            title="Tiempo"
          />
        </Card>

        <Card>
          <StepSummary stats={stats} />
        </Card>
        <Card>
          <Text selectable style={styles.prefsText}>
            {historial}
          </Text>
        </Card>
      </ScrollView>
    </>
  );
}

const styles = StyleSheet.create({
  container: {flex: 1, padding: 16, backgroundColor: '#F5F7F9'},
  header: {
    fontSize: 22,
    fontWeight: 'bold',
    marginVertical: 12,
    color: '#1A1A1A',
  },
  data: {fontSize: 16, marginBottom: 8},
  statHeader: {
    alignItems: 'center',
    marginBottom: 6,
  },
  progressHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'flex-start',
    marginBottom: 16,
  },
  progressTitle: {
    fontSize: 18,
    fontWeight: '600',
    color: '#1A1A1A',
    marginBottom: 2,
  },
  progressDate: {
    fontSize: 13,
    color: '#8E8E93',
  },
  progressBadge: {
    backgroundColor: '#007AFF',
    paddingHorizontal: 12,
    paddingVertical: 6,
    borderRadius: 20,
  },
  progressBadgeText: {
    color: '#FFFFFF',
    fontSize: 18,
    fontWeight: '600',
  },
  detailsButton: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    paddingVertical: 6,
    paddingHorizontal: 8,
    backgroundColor: '#F0F8FF',
    borderRadius: 8,
    borderWidth: 1,
    borderColor: '#E5F3FF',
  },
  detailsButtonText: {
    color: '#007AFF',
    fontSize: 12,
    fontWeight: '500',
    marginRight: 4,
  },
  statIcon: {
    width: 28,
    height: 28,
    borderRadius: 14,
    backgroundColor: '#F8F9FA',
    justifyContent: 'center',
    alignItems: 'center',
    marginRight: 8,
  },
  statValue: {
    fontSize: 18,
    fontWeight: '600',
    color: '#1A1A1A',
    flex: 1,
  },
  statLabel: {
    fontSize: 16,
    color: '#8E8E93',
    textAlign: 'center',
  },
  prefsText: {
    fontFamily: 'monospace',
    fontSize: 14,
    color: '#333',
  },
});
