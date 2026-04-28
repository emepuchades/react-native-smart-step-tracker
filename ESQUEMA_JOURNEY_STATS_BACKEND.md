# Esquema: Sistema de Estadísticas de Journey - Integración Backend

**Creado:** 28 de marzo de 2026  
**Objetivo:** Definir estructura de datos y endpoints necesarios para guardar y recuperar estadísticas de rutas (Journey Stats)

---

## 📋 FLUJO PRINCIPAL

```
1. SelectDestination (Seleccionar destino)
   ↓
2. Crear/Guardar Journey (Backend)
   ↓
3. JourneyStats (Ver estadísticas activamente)
   ↓
4. Pausar/Cambiar Destino/Completar
   ↓
5. Guardar Estado en Backend
```

---

## 🗂️ ESTRUCTURA DE DATOS PRINCIPAL

### **1. JOURNEY (Ruta/Viaje)**

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
    "address": "Valencia, Spain",
    "startLatitude": 39.4699,
    "startLongitude": -0.3763
  },
  
  "route": {
    "totalDistanceKm": 246.5,
    "routeCoordinates": [[lon, lat], [lon, lat], ...],
    "checkpoints": [
      {
        "id": 1,
        "name": "Valencia",
        "latitude": 39.4699,
        "longitude": -0.3763,
        "distanceKmFromStart": 0,
        "distanceKmToNext": 28,
        "estimatedArrivalTime": "06:30",
        "status": "completed" | "current" | "upcoming"
      },
      {
        "id": 2,
        "name": "Sagunto",
        "latitude": 39.6486,
        "longitude": -0.2736,
        "distanceKmFromStart": 28,
        "distanceKmToNext": 37,
        "estimatedArrivalTime": "09:15",
        "status": "completed"
      },
      // ... más checkpoints
    ]
  },
  
  "timing": {
    "createdAt": "2026-03-20T10:00:00Z",
    "startedAt": "2026-03-20T10:00:00Z",
    "pausedAt": null,
    "resumedAt": null,
    "completedAt": null,
    "totalElapsedHours": 38.5,
    "totalActiveDays": 5
  },
  
  "progress": {
    "totalWalkedKm": 98.5,
    "remainingKm": 148,
    "progressPercent": 40,
    "currentLocationName": "Vinaros",
    "currentLocationLat": 40.4839,
    "currentLocationLon": 0.4871,
    "tripDayNumber": 5,
    "totalTripDays": 9
  }
}
```

---

## 📊 ESTADÍSTICAS DIARIAS (DAILY_STATS)

```json
{
  "dailyStatsId": "uuid",
  "journeyId": "uuid",
  "userId": "uuid",
  "date": "2026-03-24",
  
  "steps": {
    "totalSteps": 32450,
    "dailyGoal": 10000,
    "percentageOfGoal": 324.5
  },
  
  "distance": {
    "walkedKm": 28.5,
    "routeDistanceKm": 246.5,
    "remainingKm": 148
  },
  
  "time": {
    "activeDurationMinutes": 402,
    "activeDurationFormatted": "6h 42m",
    "totalHours": 6.7
  },
  
  "performance": {
    "stepsPerMinute": 112,
    "averagePace": "12:30",
    "restTimeMinutes": 45,
    "kaloriesToday": 1845,
    "kaloriesTomorrow": null // para comparativa futura
  },
  
  "hydration": {
    "litersDrunk": 1.8,
    "literTarget": 2.5,
    "percentageOfTarget": 72
  },
  
  "currentLocation": {
    "name": "Vinaros",
    "latitude": 40.4839,
    "longitude": 0.4871,
    "checkpoint": 4
  }
}
```

---

## 📈 ESTADÍSTICAS SEMANALES (WEEKLY_STATS)

```json
{
  "weeklyStatsId": "uuid",
  "journeyId": "uuid",
  "userId": "uuid",
  "weekNumber": 12,
  "year": 2026,
  "startDate": "2026-03-23",
  "endDate": "2026-03-29",
  
  "dailyBreakdown": [
    {
      "day": "L",
      "date": "2026-03-23",
      "steps": 28500,
      "distance": 25.2,
      "activeMinutes": 380,
      "kalories": 1850
    },
    {
      "day": "M",
      "date": "2026-03-24",
      "steps": 31200,
      "distance": 27.8,
      "activeMinutes": 398,
      "kalories": 1920
    },
    // ... 7 días
  ],
  
  "weeklyTotals": {
    "totalSteps": 203100,
    "totalDistanceKm": 193.5,
    "totalActiveMinutes": 2750,
    "totalKalories": 13250
  },
  
  "weeklyAverages": {
    "avgStepsPerDay": 29014,
    "avgDistancePerDay": 27.6,
    "avgActiveMinutesPerDay": 393,
    "avgKaloriesPerDay": 1893
  }
}
```

---

## 📆 ESTADÍSTICAS MENSUALES (MONTHLY_STATS)

```json
{
  "monthlyStatsId": "uuid",
  "journeyId": "uuid",
  "userId": "uuid",
  "month": 3,
  "year": 2026,
  
  "weeklyBreakdown": [
    {
      "week": "S1",
      "weekNumber": 10,
      "startDate": "2026-03-02",
      "endDate": "2026-03-08",
      "avgSteps": 24500,
      "avgDistance": 21.8,
      "totalSteps": 171500,
      "totalDistance": 152.6
    },
    // ... 4-5 semanas
  ],
  
  "monthlyTotals": {
    "totalSteps": 924700,
    "totalDistanceKm": 823.5,
    "totalActiveMinutes": 11200,
    "totalKalories": 56200
  },
  
  "monthlyAverages": {
    "avgStepsPerDay": 29829,
    "avgDistancePerDay": 26.6,
    "avgActiveMinutesPerDay": 361,
    "avgKaloriesPerDay": 1816
  }
}
```

---

## ⏸️ ESTADO DE PAUSA (PAUSE_STATE)

```json
{
  "pauseId": "uuid",
  "journeyId": "uuid",
  "userId": "uuid",
  
  "isPaused": true | false,
  "pausedAt": "2026-03-24T14:30:00Z",
  "resumedAt": null,
  
  "stateSnapshot": {
    "lastProgressPercent": 40,
    "lastWalkedKm": 98.5,
    "lastLocationName": "Vinaros",
    "lastLocationLat": 40.4839,
    "lastLocationLon": 0.4871,
    "elapsedHours": 38.5,
    "activeMinutesToday": 402
  }
}
```

---

## 🏁 RESUMEN DE RUTA COMPLETADA (COMPLETED_JOURNEY_SUMMARY)

```json
{
  "completedSummaryId": "uuid",
  "journeyId": "uuid",
  "userId": "uuid",
  
  "journey": {
    "origin": "Valencia",
    "destination": "Tarragona",
    "totalDistanceKm": 246.5,
    "completedAt": "2026-03-28T18:45:00Z"
  },
  
  "totals": {
    "totalWalkedKm": 246.5,
    "totalSteps": 287540,
    "totalActiveMinutes": 2850,
    "totalDays": 9,
    "totalHours": 285,
    "totalCalories": 67850,
    "averageDailySteps": 31948,
    "averageDailyDistance": 27.4
  },
  
  "achievements": [
    "marathonCompleted",
    "weeklyStepGoal5Times",
    "consistentTracker"
  ]
}
```

---

## 🔌 ENDPOINTS BACKEND NECESARIOS

### **1. JOURNEY ENDPOINTS**

#### **POST /api/journeys**
- **Descripción:** Crear una nueva ruta/viaje
- **Entrada:** SelectDestination data
- **Respuesta:** Journey object dengan ID
- **Datos requeridos:**
  - destination (name, lat, lon, address)
  - origin (name, lat, lon, address, startLabel)
  - routeCoordinates
  - distanceKm
  - checkpoints

```javascript
{
  "destination": { "name", "latitude", "longitude", "address" },
  "origin": { "name", "latitude", "longitude", "address" },
  "routeCoords": [[lon, lat], ...],
  "totalDistanceKm": 246.5,
  "checkpoints": [{ "name", "lat", "lon", "distanceKm", ... }]
}
```

#### **GET /api/journeys/:journeyId**
- **Descripción:** Obtener datos de una ruta activa
- **Respuesta:** Journey object completo

#### **PATCH /api/journeys/:journeyId**
- **Descripción:** Actualizar estado de ruta (paused, active, completed)
- **Entrada:**
  ```json
  {
    "status": "paused" | "active" | "completed",
    "currentLocation": { "name", "lat", "lon" },
    "progressPercent": 40,
    "walkedKm": 98.5
  }
  ```

#### **GET /api/journeys/user/:userId**
- **Descripción:** Obtener todas las rutas del usuario (activas, pausadas, completadas)
- **Respuesta:** Array de journeys con status

---

### **2. DAILY STATS ENDPOINTS**

#### **POST /api/daily-stats**
- **Descripción:** Guardar estadísticas diarias
- **Entrada:** DailyStats object
- **Llamada:** Al final del día o cuando se pause/complete la ruta

```javascript
{
  "journeyId": "uuid",
  "date": "2026-03-24",
  "steps": { "totalSteps", "dailyGoal", "percentageOfGoal" },
  "distance": { "walkedKm", "routeDistanceKm", "remainingKm" },
  "time": { "activeDurationMinutes", "totalHours" },
  "performance": { "stepsPerMinute", "averagePace", "restTimeMinutes", "kalories" },
  "hydration": { "litersDrunk", "literTarget", "percentageOfTarget" },
  "currentLocation": { "name", "lat", "lon", "checkpoint" }
}
```

#### **GET /api/daily-stats/:journeyId**
- **Descripción:** Obtener estadísticas diarias de una ruta
- **Query params:** ?startDate=2026-03-20&endDate=2026-03-28
- **Respuesta:** Array de DailyStats

#### **PATCH /api/daily-stats/:dailyStatsId**
- **Descripción:** Actualizar una estadística diaria específica
- **Uso:** Cuando se actualiza paso a paso durante el día

---

### **3. WEEKLY STATS ENDPOINTS**

#### **POST /api/weekly-stats**
- **Descripción:** Guardar estadísticas semanales (calculadas automáticamente)
- **Entrada:** WeeklyStats object
- **Automático:** Se calcula cada domingo o al completar ruta

#### **GET /api/weekly-stats/:journeyId**
- **Descripción:** Obtener estadísticas semanales
- **Respuesta:** Array de WeeklyStats

#### **PATCH /api/weekly-stats/:weeklyStatsId**
- **Descripción:** Actualizar cálculos de semana

---

### **4. MONTHLY STATS ENDPOINTS**

#### **POST /api/monthly-stats**
- **Descripción:** Guardar estadísticas mensuales
- **Automático:** Se calcula cada fin de mes

#### **GET /api/monthly-stats/:journeyId**
- **Descripción:** Obtener estadísticas mensuales

---

### **5. PAUSE STATE ENDPOINTS**

#### **POST /api/journey-pauses**
- **Descripción:** Guardar estado cuando se pausa
- **Entrada:** PauseState object

```javascript
{
  "journeyId": "uuid",
  "isPaused": true,
  "stateSnapshot": {
    "lastProgressPercent": 40,
    "lastWalkedKm": 98.5,
    "lastLocationName": "Vinaros",
    "elapsedHours": 38.5
  }
}
```

#### **PATCH /api/journey-pauses/:pauseId**
- **Descripción:** Reanudar pausa
- **Entrada:** `{ "isPaused": false, "resumedAt": timestamp }`

---

### **6. COMPLETED JOURNEY ENDPOINTS**

#### **POST /api/completed-journeys**
- **Descripción:** Guardar resumen de ruta completada
- **Entrada:** CompletedJourneySummary object

#### **GET /api/completed-journeys/user/:userId**
- **Descripción:** Obtener historial de rutas completadas

---

## 📱 CAMBIOS EN FRONTEND

### **SelectDestination/index.js** ✏️

**Cambio 1:** Al confirmar destino (handleConfirm)
```javascript
// Antes: solo devuelve datos al componente padre
// Ahora: 
onConfirm({
  ...dataSeleccionado,
  routeCoords,
  distanceKm,
  // NUEVO:
  checkpoints: calculatedCheckpoints, // calcular en backend
  startAddress: originLabel,
  originName: originLabel,
  // Los datos irán a backend cuando se abra JourneyStats
})
```

**Acción:** Llamar a `POST /api/journeys` para crear la ruta en backend

---

### **JourneyStats.js** ✏️

**Cambio 1:** Al montar el componente
```javascript
// NUEVO: Obtener datos de backend si hay journeyId
useEffect(() => {
  if (destination?.journeyId) {
    fetchJourneyData(destination.journeyId);
  }
}, []);
```

**Cambio 2:** Actualización de progreso (cada N minutos o cada paso)
```javascript
// Llamar a PATCH /api/journeys/:journeyId
// con datos actualizados de:
// - walkedKm (de sensores)
// - currentLocation
// - progressPercent
// - elapsedHours
```

**Cambio 3:** Guardar estadísticas diarias (cada día o al pausar)
```javascript
// Llamar a POST /api/daily-stats
// con todos los datos de perforamnce del día
```

**Cambio 4:** Pausar ruta (onPauseTrip)
```javascript
// 1. Llamar PATCH /api/journeys/:journeyId { status: 'paused' }
// 2. Llamar PATCH /api/journey-pauses/:pauseId { isPaused: true }
// 3. Guardar daily-stats del día
```

**Cambio 5:** Cambiar destino (onChangeRoute)
```javascript
// 1. Completar daily-stats del día actual
// 2. Pausa la ruta actual: PATCH /api/journeys/:journeyId { status: 'paused' }
// 3. Volver a SelectDestination para elegir nuevo destino
// 4. El anterior se queda guardado en backend para retomar
```

**Cambio 6:** Completar ruta
```javascript
// 1. PATCH /api/journeys/:journeyId { status: 'completed' }
// 2. POST /api/completed-journeys (con resumen)
// 3. Mostrar pantalla de logros/resumen
// 4. No permitir editar, solo ver estadísticas
```

---

## 🔄 FLUJO DE SINCRONIZACIÓN

### **Inicio de sesión (App.js)**
```javascript
// 1. Obtener journeys activas/pausadas del usuario
GET /api/journeys/user/:userId?status=active,paused

// 2. Si hay journey activa:
//    - Mostrar JourneyStats
//    - Sincronizar con datos backend
// 3. Si No:
//    - Mostrar pantalla de seleccionar destino
```

### **Mientras está activa la ruta**
```
Cada 5-10 minutos:
  - Recopilar datos de sensores (steps, distance, location)
  - PATCH /api/journeys/:journeyId (actualizar progreso)
  - Actualizar UI localmente

Cada fin de día (23:59) o cambio de día:
  - POST /api/daily-stats (guardar stats completas del día)
  - Reset del contador diario local
  - Sincronizar con backend

Cada domingo (fin de semana):
  - Calcular weekly-stats en backend (de daily-stats)
  - Guardar POST /api/weekly-stats

Cada fin de mes:
  - Calcular monthly-stats en backend
  - Guardar POST /api/monthly-stats
```

---

## 📊 CÁLCULOS Y FÓRMULAS

### **En Frontend (Real-time)**
- `progressPercent = (walkedKm / totalKm) * 100`
- `remainingKm = totalKm - walkedKm`
- `stepsPerMinute = totalSteps / activeDurationMinutes`
- `averagePace = activeDurationMinutes / totalSteps` (formato MM:SS)
- `hydrationPercent = (litersDrunk / literTarget) * 100`
- `tripDayNumber = floor((Date.now() - startedAt) / (24 * 60 * 60 * 1000)) + 1`

### **En Backend (Acumulados)**
- `weeklyTotals = SUM(dailyStats.steps/distance/minutes for week)`
- `weeklyAverages = weeklyTotals / 7`
- `monthlyTotals = SUM(weeklyStats.totals for month)`
- `monthlyAverages = monthlyTotals / days_in_month`
- `kaloriesTarget = stepsDailyGoal * 0.05` (aprox)

---

## 🗃️ COLECCIONES/TABLAS BACKEND (MongoDB o SQL)

```
📊 journeys
  - journeyId (PK)
  - userId (FK)
  - status
  - destination
  - origin
  - route
  - timing
  - progress
  - createdAt
  - updatedAt

📊 daily_stats
  - dailyStatsId (PK)
  - journeyId (FK)
  - userId (FK)
  - date (index)
  - steps
  - distance
  - time
  - performance
  - hydration
  - currentLocation

📊 weekly_stats
  - weeklyStatsId (PK)
  - journeyId (FK)
  - userId (FK)
  - week & year (index)
  - dailyBreakdown
  - weeklyTotals
  - weeklyAverages

📊 monthly_stats
  - monthlyStatsId (PK)
  - journeyId (FK)
  - userId (FK)
  - month & year (index)
  - weeklyBreakdown
  - monthlyTotals
  - monthlyAverages

📊 pause_states
  - pauseId (PK)
  - journeyId (FK)
  - userId (FK)
  - isPaused
  - pausedAt
  - resumedAt
  - stateSnapshot

📊 completed_journeys
  - completedSummaryId (PK)
  - journeyId (FK)
  - userId (FK)
  - journey
  - totals
  - achievements
  - completedAt (index)
```

---

## ✅ ORDEN DE IMPLEMENTACIÓN

**Fase 1: Setup Básico**
- [ ] Crear archivos de servicios frontend
- [ ] Definir endpoints backend básicos
- [ ] Integrar POST /api/journeys en SelectDestination
- [ ] Integrar GET /api/journeys/:journeyId en JourneyStats

**Fase 2: Estadísticas Diarias**
- [ ] Crear DailyStats service
- [ ] Integrar POST /api/daily-stats
- [ ] Mostrar datos en JourneyStats UI

**Fase 3: Pausa y Cambio de Ruta**
- [ ] Integrar PATCH /api/journeys/:journeyId (status update)
- [ ] Crear PauseState service
- [ ] Guardar estado al pausar

**Fase 4: Estadísticas Semanales y Mensuales**
- [ ] Implementar cálculos automáticos en backend
- [ ] Integrar APIs de weekly/monthly stats

**Fase 5: Ruta Completada**
- [ ] Implementar lógica de finalización
- [ ] Guardar en CompletedJourneys
- [ ] Mostrar pantalla de resumen

**Fase 6: Sincronización y Optimización**
- [ ] Implementar sincronización periódica
- [ ] Cachear datos localmente
- [ ] Manejar conectividad
- [ ] Optimizar queries

---

## 🎯 PRÓXIMOS PASOS

1. **Crear archivo de servicios frontend:** `stepJourney/services/journeyService.js`
2. **Mockear endpoints backend** para empezar a integrar
3. **Modificar SelectDestination** para crear journey en backend
4. **Modificar JourneyStats** para leer/actualizar desde backend
5. Ir completando fase por fase

