import React from 'react';
import {View, Text, StyleSheet} from 'react-native';
import {
  Svg,
  Circle,
  G,
  Defs,
  LinearGradient,
  Stop,
  Rect,
  Polygon,
  Path,
} from 'react-native-svg';

const CircleBar = ({steps}) => {
  const dailySteps = steps;
  const dailyGoal = 10000;
  const completionPercentage = Math.min(dailySteps / dailyGoal, 1);

  const radius = 60;
  const strokeWidth = 6;
  const circumference = 2 * Math.PI * radius;
  const strokeDashoffset = circumference - circumference * completionPercentage;

  return (
    <View style={styles.progressContent}>
      <View style={styles.circleContainer}>
        <Svg
          width={radius * 2 + strokeWidth * 2}
          height={radius * 2 + strokeWidth * 2}>
          <Defs>
            <LinearGradient
              id="progressGradient"
              x1="0%"
              y1="0%"
              x2="100%"
              y2="0%">
              <Stop offset="0%" stopColor="#007AFF" />
              <Stop offset="100%" stopColor="#5AC8FA" />
            </LinearGradient>
          </Defs>
          <G
            transform={`translate(${radius + strokeWidth}, ${
              radius + strokeWidth
            })`}>
            <Circle
              r={radius}
              fill="transparent"
              stroke="#F2F2F7"
              strokeWidth={strokeWidth}
            />
            <Circle
              r={radius}
              fill="transparent"
              stroke="url(#progressGradient)"
              strokeWidth={strokeWidth}
              strokeDasharray={circumference}
              strokeDashoffset={strokeDashoffset}
              strokeLinecap="round"
              transform="rotate(-90)"
            />
          </G>
        </Svg>
        <View style={styles.circleText}>
          <Text style={styles.stepsCount}>{dailySteps.toLocaleString()}</Text>
          <Text style={styles.stepsLabel}>steps</Text>
        </View>
      </View>
    </View>
  );
};

const styles = StyleSheet.create({
  progressContent: {
    alignItems: 'center',
  },
  circleContainer: {
    position: 'relative',
    marginRight: 24,
    width: 112,
    height: 112,
    justifyContent: 'center',
    alignItems: 'center',
  },
  circleBackground: {
    position: 'absolute',
    borderColor: '#F2F2F7',
    backgroundColor: 'transparent',
  },
  progressWrapper: {
    position: 'absolute',
    flexDirection: 'row',
    overflow: 'hidden',
  },
  progressHalf: {
    position: 'absolute',
    backgroundColor: 'transparent',
  },
  progressLeft: {
    left: 0,
    borderTopWidth: 6,
    borderBottomWidth: 6,
    borderLeftWidth: 6,
    borderRightWidth: 0,
    borderTopColor: '#007AFF',
    borderBottomColor: '#007AFF',
    borderLeftColor: '#007AFF',
    borderRightColor: 'transparent',
    transformOrigin: 'right center',
  },
  progressRight: {
    right: 0,
    borderTopWidth: 6,
    borderBottomWidth: 6,
    borderRightWidth: 6,
    borderLeftWidth: 0,
    borderTopColor: '#5AC8FA',
    borderBottomColor: '#5AC8FA',
    borderRightColor: '#5AC8FA',
    borderLeftColor: 'transparent',
    transformOrigin: 'left center',
  },
  innerCircle: {
    position: 'absolute',
    backgroundColor: '#FFFFFF',
    elevation: 2,
    shadowColor: '#000',
    shadowOffset: {width: 0, height: 1},
    shadowOpacity: 0.1,
    shadowRadius: 2,
  },
  circleText: {
    position: 'absolute',
    justifyContent: 'center',
    alignItems: 'center',
    zIndex: 10,
  },
  stepsCount: {
    fontSize: 18,
    fontWeight: '700',
    color: '#1A1A1A',
  },
  stepsLabel: {
    fontSize: 14,
    color: '#8E8E93',
    marginTop: 1,
  },
  percentageText: {
    fontSize: 12,
    fontWeight: '600',
    color: '#007AFF',
    marginTop: 2,
  },
  progressDetails: {
    flex: 1,
    justifyContent: 'center',
  },
  motivationContainer: {
    alignItems: 'flex-start',
  },
  motivationText: {
    fontSize: 18,
    fontWeight: '600',
    color: '#1A1A1A',
    marginBottom: 4,
  },
  remainingText: {
    fontSize: 14,
    color: '#8E8E93',
    lineHeight: 20,
  },
});

export default CircleBar;
