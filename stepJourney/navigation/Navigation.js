import React, { useState } from "react";
import {
  View,
  Text,
  TouchableOpacity,
  StyleSheet,
  SafeAreaView,
} from "react-native";

import HomeScreen from "../screens/HomeScreen/HomeScreen";
import Stats from "../screens/Stats/Stats";
import Header from "../components/Header/Header";

export default function Navigation() {
  const [currentScreen, setCurrentScreen] = useState("Home");

  const renderScreen = () => {
    switch (currentScreen) {
      case "Home":
        return <HomeScreen />;
      case "Stats":
        return <Stats />;
      case "Journey":
        return <Stats />;
      case "Profile":
        return <Stats />;
      default:
        return <HomeScreen />;
    }
  };

  return (
    <SafeAreaView style={styles.container}>
      <Header title="StepJourney" />
      <View style={styles.content}>{renderScreen()}</View>
      <View style={styles.navBar}>
        {["Home", "Stats", "Journey", "Profile"].map((screen) => (
          <TouchableOpacity
            key={screen}
            style={styles.navItem}
            onPress={() => setCurrentScreen(screen)}
          >
            <Text
              style={currentScreen === screen ? styles.active : styles.inactive}
            >
              {screen}
            </Text>
          </TouchableOpacity>
        ))}
      </View>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: "#F5F7F9" },
  content: { flex: 1 },
  navBar: {
    flexDirection: "row",
    position: "fixed",
    justifyContent: "space-around",
    paddingVertical: 10,
    borderTopWidth: 1,
    borderTopColor: "#ddd",
    backgroundColor: "#fff",
  },
  navItem: { alignItems: "center" },
  active: { color: "#007bff", fontWeight: "bold" },
  inactive: { color: "#888" },
});
