import React, {useState} from 'react';
import {View, Text, StyleSheet, TouchableOpacity} from 'react-native';
import Ionicons from 'react-native-vector-icons/Ionicons';
import { useNavigation } from '@react-navigation/native';

function BottomNavigation() {
  const [selectedTab, setSelectedTab] = useState('home');
  const navigation = useNavigation();

  const navigateTo = screen => {
    setSelectedTab(screen);
    navigation.navigate(screen);
  };

  return (
    <View style={styles.bottomNavigation}>
      <TouchableOpacity
        style={styles.navItem}
        onPress={() => navigateTo('home')}>
        <Ionicons
          name={selectedTab === 'home' ? 'home' : 'home-outline'}
          size={24}
          color={selectedTab === 'home' ? '#007AFF' : '#8E8E93'}
        />
        <Text
          style={[
            styles.navLabel,
            selectedTab === 'home' && styles.navLabelActive,
          ]}>
          Home
        </Text>
      </TouchableOpacity>

      <TouchableOpacity
        style={styles.navItem}
        onPress={() => navigateTo('stats')}>
        <Ionicons
          name={selectedTab === 'stats' ? 'pulse' : 'pulse-outline'}
          size={24}
          color={selectedTab === 'stats' ? '#007AFF' : '#8E8E93'}
        />
        <Text
          style={[
            styles.navLabel,
            selectedTab === 'stats' && styles.navLabelActive,
          ]}>
          Activity Stats
        </Text>
      </TouchableOpacity>

      <TouchableOpacity
        style={styles.navItem}
        onPress={() => navigateTo('journey')}>
        <Ionicons
          name={
            selectedTab === 'journey' ? 'trending-up' : 'trending-up-outline'
          }
          size={24}
          color={selectedTab === 'journey' ? '#007AFF' : '#8E8E93'}
        />
        <Text
          style={[
            styles.navLabel,
            selectedTab === 'journey' && styles.navLabelActive,
          ]}>
          Journey Progress
        </Text>
      </TouchableOpacity>

      <TouchableOpacity
        style={styles.navItem}
        onPress={() => navigateTo('profile')}>
        <Ionicons
          name={selectedTab === 'profile' ? 'person' : 'person-outline'}
          size={24}
          color={selectedTab === 'profile' ? '#007AFF' : '#8E8E93'}
        />
        <Text
          style={[
            styles.navLabel,
            selectedTab === 'profile' && styles.navLabelActive,
          ]}>
          Profile
        </Text>
      </TouchableOpacity>
    </View>
  );
}
const styles = StyleSheet.create({
  bottomNavigation: {
    flexDirection: 'row',
    position: 'fixed',
    bottom: 0,
    backgroundColor: '#FFFFFF',
    justifyContent: 'space-around',
    alignItems: 'center',
    paddingVertical: 10,
    borderTopWidth: 1,
    borderTopColor: '#F2F2F7',
    paddingBottom: 20,
    paddingTop: 10,
  },
  navItem: {
    alignItems: 'center',
    justifyContent: 'center',
    flex: 1,
  },
  navLabel: {
    color: '#8E8E93',
    fontSize: 11,
    marginTop: 4,
    fontWeight: '500',
  },
  navLabelActive: {
    color: '#007AFF',
  },
});

export default BottomNavigation;
