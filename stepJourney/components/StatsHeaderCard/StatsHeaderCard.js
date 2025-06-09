import React from "react";
import { StyleSheet, Text, View } from "react-native";

function StatsHeaderCard({ icon, stats, title }) {
  return (
    <View style={styles.statHeader}>
      <View style={styles.statIcon}>
        <Text>{icon}</Text>
      </View>
      <Text style={styles.statValue}>{stats}</Text>
      <Text style={styles.statLabel}>{title}</Text>
    </View>
  );
}

export default StatsHeaderCard;

const styles = StyleSheet.create({
  statHeader: {
    alignItems: "center",
    marginBottom: 6,
  },
  statIcon: {
    width: 28,
    height: 28,
    borderRadius: 14,
    backgroundColor: "#F8F9FA",
    justifyContent: "center",
    alignItems: "center",
    marginRight: 8,
  },
  statValue: {
    fontSize: 17,
    fontWeight: "600",
    color: "#1A1A1A",
    flex: 1,
  },
  statLabel: {
    fontSize: 15,
    color: "#8E8E93",
    textAlign: "center",
  },
  prefsText: {
    fontFamily: "monospace",
    fontSize: 14,
    color: "#333",
  },
});
