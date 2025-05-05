import { NativeModules, NativeEventEmitter } from 'react-native';

const { StepTrackerModule } = NativeModules;

export const StepTracker = StepTrackerModule;
export const StepTrackerEvents = new NativeEventEmitter(StepTrackerModule);
