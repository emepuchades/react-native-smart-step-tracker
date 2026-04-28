# Sistema Journey Stats - SIMPLIFICADO

**Principio clave:** Reutilizar datos que ya existen en `daily_history`  
No duplicar pasos, distancia, calorías → Ya están guardados por día

---

## 🗂️ SOLO 2 TABLAS NUEVAS

### **1. JOURNEYS** (Rutas activas)

```json
{
  "journeyId": "uuid",
  "userId": "uuid",
  "status": "active" | "paused" | "completed",
  
  "destination": {
    "name": "Tarragona",
    "latitude": 41.1186,
    "longitude": 1.2526,
    "address": "Tarragona, Spain"
  },
  
  "origin": {
    "name": "Valencia",
    "latitude": 39.4699,
    "longitude": -0.3763,
    "address": "Valencia, Spain"
  },
  
  "route": {
    "totalDistanceKm": 246.5,
    "routeCoordinates": [[lon, lat], [lon, lat], ...],
    "checkpoints": [
      {
        "id": 1,
        "name": "Valencia",
        "distanceFromStart": 0,
        "distanceToNext": 28
      },
      {
        "id": 2,
        "name": "Sagunto",
        "distanceFromStart": 28,
        "distanceToNext": 37
      },
      // ... más checkpoints
    ]
  },
  
  "timing": {
    "createdAt": "2026-03-20T10:00:00Z",
    "startedAt": "2026-03-20T10:00:00Z",
    "completedAt": null
  }
}
```

### **2. JOURNEY_DAILY_LOG** (Registro diario de cada ruta)

```json
{
  "id": "uuid",
  "journeyId": "uuid",
  "userId": "uuid",
  "date": "2026-03-24",
  
  // Del schema SelectDestination
  "tripDayNumber": 5,        // qué día de la ruta es
  "isPaused": false,         // si estaba pausado este día
  
  // Se obtienen de daily_history (NO se guardan aquí, solo referencia)
  "stepsFromSensor": 32450,
  "distanceFromSensor": 28.5,
  "caloriesFromSensor": 1845,
  "activeMinutesFromSensor": 402,
  
  // Datos específicos del Journey
  "currentCheckpoint": 4,
  "currentLocationName": "Vinaros",
  "currentLocationLat": 40.4839,
  "currentLocationLon": 0.4871,
  
  // Progreso acumulado en la ruta
  "totalWalkedKmInJourney": 98.5,
  "progressPercent": 40
}
```

---

## 🔌 ENDPOINTS SIMPLIFICADOS

### **Journey Management**

**POST /api/journeys**
```javascript
// Crear journey
{
  "destination": { "name", "latitude", "longitude", "address" },
  "origin": { "name", "latitude", "longitude", "address" },
  "routeCoords": [[lon, lat], ...],
  "totalDistanceKm": 246.5,
  "checkpoints": [{ "name", "distanceFromStart", "distanceToNext" }]
}
// Respuesta: { journeyId, createdAt }
```

**GET /api/journeys/:journeyId**
```javascript
// Obtener ruta activa
// Respuesta: Journey object completo
```

**PATCH /api/journeys/:journeyId**
```javascript
// Actualizar estado
{
  "status": "paused" | "active" | "completed",
  "currentCheckpoint": 4,
  "currentLocationName": "Vinaros",
  "progressPercent": 40,
  "walkedKmInJourney": 98.5
}
```

**GET /api/journeys/user/:userId**
```javascript
// Obtener todas las rutas del usuario
// ?status=active,paused,completed
// Respuesta: Array de journeys
```

---

### **Journey Daily Log**

**POST /api/journey-daily-log**
```javascript
// Guardar al final del día (automático)
{
  "journeyId": "uuid",
  "date": "2026-03-24",
  "tripDayNumber": 5,
  "isPaused": false,
  "currentCheckpoint": 4,
  "currentLocationName": "Vinaros",
  "currentLocationLat": 40.4839,
  "currentLocationLon": 0.4871,
  "totalWalkedKmInJourney": 98.5,
  "progressPercent": 40
  // Los steps/distance/calories se toman de daily_history directamente
}
```

**GET /api/journey-daily-log/:journeyId**
```javascript
// Obtener el log completo de una ruta
// Respuesta: Array de logs por día
```

---

## 📊 CÓMO SE MUESTRAN LAS STATS EN JourneyStats.js

```javascript
// 1. Obtener journey data
const journey = await GET /api/journeys/:journeyId
// Tenemos: destino, origen, ruta, checkpoints, totalKm

// 2. Obtener hoy's daily_history (YA EXISTE en Android)
const todaySteps = await StepTrackerModule.getTodayStats()
// Tenemos: steps, distance, calories, activeDuration

// 3. Obtener journey_daily_log de hoy
const journeyLog = await GET /api/journey-daily-log/:journeyId?date=today
// Tenemos: tripDayNumber, currentCheckpoint, progressPercent, walkedKmInJourney

// 4. Mostrar en UI
Journey Stats = {
  destination: journey.destination.name,
  origin: journey.origin.name,
  
  dailyStats: {
    steps: todaySteps.steps,              // De daily_history
    distance: todaySteps.distance,        // De daily_history
    calories: todaySteps.calories,        // De daily_history
    activeDuration: todaySteps.time       // De daily_history
  },
  
  journeyProgress: {
    totalWalked: journeyLog.totalWalkedKmInJourney,
    remaining: journey.totalDistanceKm - journeyLog.totalWalkedKmInJourney,
    progressPercent: journeyLog.progressPercent,
    tripDayNumber: journeyLog.tripDayNumber,
    currentCheckpoint: journeyLog.currentCheckpoint
  }
}
```

---

## 🔄 FLUJO OPERATIVO

### **Al Seleccionar Destino (SelectDestination)**
```
1. Usuario confirma destino
2. POST /api/journeys (crear journey)
3. Guardar journeyId en contexto/estado
4. Ir a JourneyStats
```

### **En JourneyStats (Activo)**
```
1. GET /api/journeys/:journeyId (datos de la ruta)
2. GET StepTrackerModule.getTodayStats() (pasos de hoy del sensor)
3. GET /api/journey-daily-log/:journeyId?date=today (progreso en la ruta)
4. Mostrar todo
```

### **Cada Día (automático o al cambiar de día)**
```
1. Backend calcula al final del día (cron job o trigger)
2. POST /api/journey-daily-log (guardar el log del día)
   - Toma steps/distance/calories de daily_history
   - Guarda tripDayNumber, currentCheckpoint, etc
```

### **Al Pausar la Ruta**
```
1. PATCH /api/journeys/:journeyId { status: 'paused' }
2. POST /api/journey-daily-log con isPaused: true
3. Los pasos del sensor SIGUEN guardándose en daily_history
   (pero no se cuentan para la ruta si está pausada)
```

### **Al Cambiar de Destino**
```
1. Pausa la ruta actual: PATCH status='paused'
2. POST /api/journey-daily-log (save before changing)
3. Volver a SelectDestination
4. POST /api/journeys (crear nueva ruta)
5. La ruta anterior queda pausada en backend, se puede retomar
```

### **Al Completar Ruta**
```
1. PATCH /api/journeys/:journeyId { status: 'completed' }
2. POST /api/journey-daily-log (last day's log)
3. Guardar en tabla completed_journeys (resumen)
4. Mostrar pantalla de logros
```

---

## 📋 CAMBIOS EN CÓDIGO FRONTEND

### **SelectDestination/index.js** (1 cambio)
```javascript
// En handleConfirm()
// AÑADIR: Crear journey en backend
const response = await journeyService.createJourney({
  destination: selectedLocation,
  origin: currentLocation,
  routeCoords,
  totalDistanceKm: distanceKm,
  checkpoints: calculatedCheckpoints
});

onConfirm({
  ...selectedLocation,
  journeyId: response.journeyId,  // NUEVO
  routeCoords,
  distanceKm
});
```

### **JourneyStats.js** (3-4 cambios)
```javascript
// 1. Al montar: cargar datos del journey
useEffect(() => {
  if (!destination?.journeyId) return;
  loadJourneyData(destination.journeyId);
}, [destination?.journeyId]);

// 2. Actualizar progreso (cada vez que cambian los pasos)
const updateJourneyProgress = async () => {
  await journeyService.updateJourneyProgress(journeyId, {
    currentCheckpoint,
    currentLocationName,
    progressPercent,
    walkedKmInJourney
  });
};

// 3. Al pausar
const handlePauseTrip = async () => {
  await journeyService.pauseJourney(journeyId);
  // Los pasos se siguen guardando en daily_history
};

// 4. Al completar
const handleCompleteJourney = async () => {
  await journeyService.completeJourney(journeyId);
};
```

---

## 🗃️ TABLAS BACKEND (MongoDB o SQL)

```sql
-- TABLA 1: Rutas activas/pausadas/completadas
CREATE TABLE journeys (
  journeyId UUID PRIMARY KEY,
  userId UUID NOT NULL,
  status ENUM('active', 'paused', 'completed'),
  
  destination_name VARCHAR(255),
  destination_lat FLOAT,
  destination_lon FLOAT,
  destination_address VARCHAR(255),
  
  origin_name VARCHAR(255),
  origin_lat FLOAT,
  origin_lon FLOAT,
  origin_address VARCHAR(255),
  
  route_coords JSON,
  total_distance_km FLOAT,
  checkpoints JSON,
  
  created_at TIMESTAMP,
  started_at TIMESTAMP,
  completed_at TIMESTAMP,
  
  FOREIGN KEY (userId) REFERENCES users(userId),
  INDEX (userId),
  INDEX (status)
);

-- TABLA 2: Log diario de cada ruta
CREATE TABLE journey_daily_log (
  id UUID PRIMARY KEY,
  journeyId UUID NOT NULL,
  userId UUID NOT NULL,
  date DATE NOT NULL,
  
  trip_day_number INT,
  is_paused BOOLEAN,
  
  current_checkpoint INT,
  current_location_name VARCHAR(255),
  current_location_lat FLOAT,
  current_location_lon FLOAT,
  
  total_walked_km_in_journey FLOAT,
  progress_percent FLOAT,
  
  created_at TIMESTAMP,
  
  FOREIGN KEY (journeyId) REFERENCES journeys(journeyId),
  FOREIGN KEY (userId) REFERENCES users(userId),
  UNIQUE (journeyId, date),
  INDEX (journeyId),
  INDEX (userId)
);

-- TABLA 3: Resumen de rutas completadas (opcional)
CREATE TABLE completed_journeys (
  id UUID PRIMARY KEY,
  userId UUID NOT NULL,
  origin_name VARCHAR(255),
  destination_name VARCHAR(255),
  total_distance_km FLOAT,
  total_steps INT,
  total_days INT,
  completed_at TIMESTAMP,
  
  FOREIGN KEY (userId) REFERENCES users(userId),
  INDEX (userId),
  INDEX (completed_at)
);
```

---

## ✅ RESUMEN: Qué es NUEVO vs QUÉ REUTILIZA

| Dato | Nuevo en Backend | Reutiliza | Fuente |
|------|---|---|---|
| Steps diarios | ❌ | ✅ | `daily_history` (sensor Android) |
| Distance diarios | ❌ | ✅ | `daily_history` (sensor Android) |
| Calories | ❌ | ✅ | `daily_history` (sensor Android) |
| Active minutes | ❌ | ✅ | `daily_history` (sensor Android) |
| Destino, origen, ruta | ✅ | ❌ | Nueva tabla `journeys` |
| Checkpoints | ✅ | ❌ | Nueva tabla `journeys` |
| Progreso en ruta | ✅ | ❌ | Nueva tabla `journey_daily_log` |
| Trip day number | ✅ | ❌ | Nueva tabla `journey_daily_log` |
| Pausado/Activo | ✅ | ❌ | Nueva tabla `journeys.status` |

---

## 🎯 VENTAJAS DE ESTE ENFOQUE

1. **No hay duplicación:** Los pasos/distance/calories están en un lugar (daily_history)
2. **Más simple:** Solo 2 tablas nuevas vs 5-6 antes
3. **Flexible:** Si está pausado, los pasos se siguen contando en daily_history (para otras cosas)
4. **Eficiente:** Las queries son simples JOIN entre journeys + daily_history
5. **Mantenible:** Cambios en stats generales no afectan al Journey

