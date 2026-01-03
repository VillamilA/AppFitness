import 'dart:async';
import 'package:sensors_plus/sensors_plus.dart';
import 'package:permission_handler/permission_handler.dart';
import '../../domain/entities/step_data.dart';
import 'notification_datasource.dart';
import 'dart:math';

/// DataSource para acelerómetro usando el plugin sensors_plus
/// - Ya no usamos Platform Channels directamente
/// - El plugin sensors_plus maneja toda la lógica nativa
abstract class AccelerometerDataSource {
  Stream<StepData> get stepStream;
  Future<void> startCounting();
  Future<void> stopCounting();
  Future<bool> requestPermissions();
}

class AccelerometerDataSourceImpl implements AccelerometerDataSource {
  final _notificationDataSource = NotificationDataSource();
  int _lastNotifiedStep = 0;
  int _stepCount = 0;
  
  StreamSubscription<AccelerometerEvent>? _subscription;
  final _stepController = StreamController<StepData>.broadcast();
  
  // Variables para detección de pasos
  double _lastMagnitude = 0;
  bool _isStepInProgress = false;
  DateTime _lastStepTime = DateTime.now();
  
  // Umbrales
  static const double _stepThreshold = 12.0; // Umbral para detectar pasos
  static const double _fallThreshold = 30.0; // Umbral para detectar caídas
  static const int _minTimeBetweenSteps = 250; // ms entre pasos

  @override
  Stream<StepData> get stepStream => _stepController.stream;

  @override
  Future<void> startCounting() async {
    print('▶️ Iniciando contador - Reseteando tracking de notificaciones');
    _lastNotifiedStep = 0;
    _stepCount = 0;
    
    // Escuchar eventos del acelerómetro
    _subscription = accelerometerEvents.listen((AccelerometerEvent event) {
      // Calcular magnitud del acelerómetro
      final magnitude = sqrt(
        event.x * event.x + 
        event.y * event.y + 
        event.z * event.z
      );
      
      // 🚨 DETECTAR CAÍDA
      if (magnitude > _fallThreshold) {
        print('🚨 CAÍDA DETECTADA - Enviando notificación');
        _notificationDataSource.showNotification(
          '⚠️ CAÍDA DETECTADA',
          'Se detectó un impacto fuerte. Magnitud: ${magnitude.toStringAsFixed(1)} m/s²',
        );
        
        _stepController.add(StepData(
          stepCount: _stepCount,
          activityType: ActivityType.stationary,
          magnitude: magnitude,
        ));
        return;
      }
      
      // Detectar pasos
      _detectStep(magnitude);
    });
  }

  void _detectStep(double magnitude) {
    final now = DateTime.now();
    final timeSinceLastStep = now.difference(_lastStepTime).inMilliseconds;
    
    // Detectar pico en la magnitud (paso)
    if (!_isStepInProgress && 
        magnitude > _stepThreshold && 
        magnitude > _lastMagnitude &&
        timeSinceLastStep > _minTimeBetweenSteps) {
      
      _isStepInProgress = true;
      _stepCount++;
      _lastStepTime = now;
      
      print('👟 Pasos: $_stepCount');
      
      // Determinar tipo de actividad basado en la magnitud
      ActivityType activityType;
      if (magnitude < 13) {
        activityType = ActivityType.walking;
      } else {
        activityType = ActivityType.running;
      }
      
      _stepController.add(StepData(
        stepCount: _stepCount,
        activityType: activityType,
        magnitude: magnitude,
      ));
      
      // 🔔 NOTIFICACIÓN: Cada 30 pasos
      if (_stepCount > 0 && 
          _stepCount % 30 == 0 && 
          _stepCount != _lastNotifiedStep) {
        print('🎉 META ALCANZADA - $_stepCount pasos - Enviando notificación');
        _lastNotifiedStep = _stepCount;
        _notificationDataSource.showNotification(
          '🎉 Meta Alcanzada',
          '¡Has completado $_stepCount pasos!',
        );
      }
    } else if (_isStepInProgress && magnitude < _lastMagnitude) {
      _isStepInProgress = false;
    }
    
    _lastMagnitude = magnitude;
  }

  @override
  Future<void> stopCounting() async {
    await _subscription?.cancel();
    _subscription = null;
  }

  @override
  Future<bool> requestPermissions() async {
    final activityStatus = await Permission.activityRecognition.request();
    final sensorsStatus = await Permission.sensors.request();
    return activityStatus.isGranted && sensorsStatus.isGranted;
  }
  
  void dispose() {
    _subscription?.cancel();
    _stepController.close();
  }
}
