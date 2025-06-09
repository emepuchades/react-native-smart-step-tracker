import React from 'react';
import { NavigationContainer } from '@react-navigation/native';
import { createNativeStackNavigator } from '@react-navigation/native-stack';
import HomeScreen from '../screens/HomeScreen/HomeScreen';
import Stats from '../screens/Stats/Stats';
import BottomNavigation from '../components/BottomNavigation/BottomNavigation';
import JourneyStats from '../screens/JourneyStats/JourneyStats';
import Profile from '../screens/Profile/Profile';

const Stack = createNativeStackNavigator();

export default function AppNavigaton() {
  return (
    <NavigationContainer>
      <Stack.Navigator
        initialRouteName="home"
        screenOptions={{
          headerShown: false,
        }}
      >
        <Stack.Screen name="home" component={HomeScreen} />
        <Stack.Screen name="stats" component={Stats} />
        <Stack.Screen name="journey" component={JourneyStats} />
        <Stack.Screen name="profile" component={Profile} />
      </Stack.Navigator>
      <BottomNavigation />
    </NavigationContainer>
  );
}
